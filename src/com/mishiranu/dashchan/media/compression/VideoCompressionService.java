package com.mishiranu.dashchan.media.compression;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import com.google.common.collect.ImmutableList;
import com.mishiranu.dashchan.R;
import java.io.File;
import java.util.Collections;
import java.util.UUID;

/** User-started media export, not a player service and never a posting retry. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class VideoCompressionService extends Service {
	private static final String CHANNEL = "video-compression", CANCEL = "cancel";
	private static final int NOTIFICATION = 14721;
	private static VideoCompressionJob pending;
	private static VideoCompressionService active;
	private final Handler handler = new Handler(android.os.Looper.getMainLooper());
	private VideoCompressionJob job;
	private Transformer transformer;
	private File output;
	private String mime;
	private int generation, pass, videoBitrate;
	private boolean triedAvc;
	private boolean forceEncode;
	private boolean finishing;
	private android.os.PowerManager.WakeLock wakeLock;
	private final androidx.lifecycle.Observer<VideoCompressionJob> stateObserver = value -> {
		if (value.phase == VideoCompressionJob.Phase.READY) { value.phase = VideoCompressionJob.Phase.RUNNING; value.changed(); next(); }
		else if (value.phase == VideoCompressionJob.Phase.ERROR || value.phase == VideoCompressionJob.Phase.CANCELLED
				|| value.phase == VideoCompressionJob.Phase.DONE) finish();
	};
	private long lastProgress;
	public static boolean isBusy() { return pending != null || active != null; }

	public static boolean start(Context context, VideoCompressionJob job) {
		if (pending != null || active != null) { job.fail(R.string.compression_busy); return false; }
		pending = job;
		try { ContextCompat.startForegroundService(context, new Intent(context, VideoCompressionService.class)); return true; }
		catch (RuntimeException e) { pending = null; job.fail(R.string.compression_start_failed); return false; }
	}
	public static void cancel(VideoCompressionJob job) {
		job.cancel();
		if (pending == job) pending = null;
		if (active != null && active.job == job) active.finish();
	}
	@Override public IBinder onBind(Intent intent) { return null; }
	@Override public void onCreate() {
		super.onCreate();
		// Only this exporter's own abandoned files; never traverse or delete the source draft cache.
		File directory = new File(getCacheDir(), "video-compression");
		File[] stale = directory.listFiles();
		if (stale != null) for (File file : stale) {
			if (file.isFile() && file.getName().matches("[0-9a-f-]{36}\\.mp4")) file.delete();
		}
	}
	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (CANCEL.equals(intent != null ? intent.getAction() : null)) {
			if (job != null && job.id.equals(intent.getStringExtra("job"))) { job.cancel(); finish(); }
			else if (job == null) stopSelf();
			return START_NOT_STICKY;
		}
		if (job != null) return START_NOT_STICKY;
		finishing = false;
		job = pending; pending = null; active = this;
		try {
			NotificationManager manager = getSystemService(NotificationManager.class);
			manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.compression_title), NotificationManager.IMPORTANCE_LOW));
			int type = Build.VERSION.SDK_INT >= 35 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
					: ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
			startForeground(NOTIFICATION, notification().build(), type);
		} catch (RuntimeException e) { if (job != null) job.fail(R.string.compression_start_failed); finish(); return START_NOT_STICKY; }
		if (job == null || !job.current()) { finish(); return START_NOT_STICKY; }
		job.changes.observeForever(stateObserver);
		if (job != null && job.phase == VideoCompressionJob.Phase.READY) { job.phase = VideoCompressionJob.Phase.RUNNING; job.changed(); next(); }
		return START_NOT_STICKY;
	}
	private NotificationCompat.Builder notification() {
		Intent cancel = new Intent(this, VideoCompressionService.class).setAction(CANCEL);
		if (job != null) cancel.setData(Uri.parse("slooop-compression:" + job.id)).putExtra("job", job.id);
		PendingIntent action = PendingIntent.getService(this, 0, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		String text = job != null && job.percent >= 0 ? getString(R.string.compression_progress, job.percent,
				Math.min(job.index + 1, job.queue.size()), job.queue.size()) : getString(R.string.compression_preparing);
		if (job != null && job.attempt > 1) text += " · " + getString(R.string.compression_retry, job.attempt);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
				.setContentTitle(getString(R.string.compression_title)).setContentText(text)
				.setProgress(100, job != null ? Math.max(0, job.percent) : 0, job == null || job.percent < 0)
				.setOnlyAlertOnce(true).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
				.addAction(0, getString(android.R.string.cancel), action);
		Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (open != null) builder.setContentIntent(PendingIntent.getActivity(this, NOTIFICATION, open,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
		return builder;
	}
	private final Runnable progress = new Runnable() {
		@Override public void run() {
			if (job == null || transformer == null) return;
			if (!job.current()) { job.cancel(); finish(); return; }
			ProgressHolder value = new ProgressHolder();
			if (transformer.getProgress(value) == Transformer.PROGRESS_STATE_AVAILABLE) {
				job.percent = CompressionBudget.progress(job.completedMs, job.queue.get(job.index).durationMs, job.totalMs, value.progress);
			} else job.percent = -1;
			job.changed();
			try { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification().build()); }
			catch (RuntimeException ignored) {}
			// A stalled export must not keep a foreground service/codecs alive indefinitely.
			if (value.progress != previousProgress) { previousProgress = value.progress; lastProgress = android.os.SystemClock.elapsedRealtime(); }
			if (android.os.SystemClock.elapsedRealtime() - lastProgress > 180_000) { job.fail(R.string.compression_failed); finish(); return; }
			handler.postDelayed(this, 1000);
		}
	};
	private int previousProgress;
	private void next() {
		if (job == null) return;
		if (!job.current() || job.phase == VideoCompressionJob.Phase.ERROR) { finish(); return; }
		if (job.index == job.queue.size()) {
			job.percent = 100; job.phase = VideoCompressionJob.Phase.DONE; job.changed(); finish(); return;
		}
		triedAvc = false; forceEncode = false; pass = 0;
		VideoCompressionJob.Source source = job.queue.get(job.index);
		mime = "avc".equals(job.codec) || "auto".equals(job.codec) && !job.preferHevc ? HardwareVideoCodecs.AVC : HardwareVideoCodecs.HEVC;
		if (!canCopyVideo(source) && !HardwareVideoCodecs.available(mime) && "auto".equals(job.codec)) { mime = HardwareVideoCodecs.AVC; triedAvc = true; }
		if (!canCopyVideo(source) && !HardwareVideoCodecs.available(mime)) { job.fail(R.string.compression_no_encoder); finish(); return; }
		if (canCopyVideo(source) && source.name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
				&& (!source.hasAudio || MimeTypes.AUDIO_AAC.equals(source.audioMime))) {
			job.reuseOriginal(source, getString(R.string.compression_already_codec, codecLabel(mime)));
			handler.post(this::next); return;
		}
		videoBitrate = chooseBitrate(source);
		export();
	}
	private int chooseBitrate(VideoCompressionJob.Source source) {
		return VideoConversionPolicy.bitrate(source.videoBitrate, source.videoMime, mime,
				(long) (source.target * 0.96 * 8000.0 / source.durationMs - audioBitrate(source)), source.shrinking);
	}
	private String codecLabel(String type) { return HardwareVideoCodecs.HEVC.equals(type) ? "H.265" : "H.264"; }
	private boolean canCopyVideo(VideoCompressionJob.Source source) {
		return !forceEncode && VideoConversionPolicy.canCopyVideo(source.videoMime, mime, source.hdr, source.shrinking);
	}
	private int audioBitrate(VideoCompressionJob.Source source) {
		return source.hasAudio ? (int) Math.max(8_000, Math.min(96_000, source.target * 0.96 * 8000.0 / source.durationMs * 0.12)) : 0;
	}
	private void export() {
		if (!job.current()) { finish(); return; }
		VideoCompressionJob.Source source = job.queue.get(job.index);
		try {
			if (wakeLock == null) {
				wakeLock = getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
						getPackageName() + ":VideoCompression"); wakeLock.setReferenceCounted(false);
			}
			if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
			File directory = new File(getCacheDir(), "video-compression");
			if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException();
			output = new File(directory, UUID.randomUUID() + ".mp4");
			final String requestedMime = mime;
			boolean copyVideo = canCopyVideo(source);
			DefaultEncoderFactory factory = new DefaultEncoderFactory.Builder(this)
					.setVideoEncoderSelector(type -> type.equals(requestedMime)
							? ImmutableList.copyOf(HardwareVideoCodecs.find(type, true)) : ImmutableList.of())
					.setRequestedVideoEncoderSettings(copyVideo ? VideoEncoderSettings.DEFAULT : new VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
					.setRequestedAudioEncoderSettings(!source.shrinking ? AudioEncoderSettings.DEFAULT
							: new AudioEncoderSettings.Builder().setBitrate(Math.max(8_000, audioBitrate(source))).build())
					.setEnableFallback(true).build();
			// Scale only when the target bitrate cannot reasonably preserve the source pixel count.
			double scale = source.shrinking ? Math.min(1.0, Math.sqrt(videoBitrate / (source.frameRate * 0.075 * source.width * (double) source.height))) : 1.0;
			int width = Math.max(2, ((int) (source.width * scale) / 2) * 2);
			int height = Math.max(2, ((int) (source.height * scale) / 2) * 2);
			EditedMediaItem.Builder itemBuilder = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source.file)));
			if (source.shrinking) itemBuilder.setEffects(new Effects(Collections.emptyList(), Collections.singletonList(
					Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))));
			EditedMediaItem item = itemBuilder.build();
			int current = ++generation;
			transformer = new Transformer.Builder(this).setVideoMimeType(mime).setAudioMimeType(MimeTypes.AUDIO_AAC)
					.setEncoderFactory(factory).addListener(new Transformer.Listener() {
						@Override public void onCompleted(Composition composition, ExportResult result) {
							if (current != generation || !job.current()) return;
							handler.removeCallbacks(progress); transformer = null;
							com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression output video_process="
									+ result.videoConversionProcess + " audio_process=" + result.audioConversionProcess);
							if (output.length() > 0 && output.length() <= source.target) {
								File completed = output; output = null;
								String label = codecLabel(result.videoMimeType);
								if (result.videoConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED)
									label = getString(R.string.compression_copied_codec, label);
								job.importResult(source, completed, label, () -> next());
							} else retry(false);
						}
						@Override public void onError(Composition composition, ExportResult result, ExportException exception) {
							if (current != generation || !job.current()) return;
							com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression export_error=" + exception.errorCode);
							handler.removeCallbacks(progress); transformer = null; retry(true);
						}
					}).build();
			job.attempt = ++pass; job.percent = -1; job.changed();
			lastProgress = android.os.SystemClock.elapsedRealtime(); previousProgress = -1;
			Composition composition = new Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(item)))
					.setHdrMode(source.hdr ? Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL : Composition.HDR_MODE_KEEP_HDR).build();
			transformer.start(composition, output.getPath()); handler.post(progress);
		} catch (Exception e) { retry(true); }
	}
	private void retry(boolean error) {
		handler.removeCallbacks(progress);
		if (transformer != null) { try { transformer.cancel(); } catch (RuntimeException ignored) {} transformer = null; }
		long length = output != null ? output.length() : 0;
		if (output != null) { output.delete(); output = null; }
		if (!job.current()) { finish(); return; }
		if (error && "auto".equals(job.codec) && HardwareVideoCodecs.HEVC.equals(mime) && !triedAvc
				&& HardwareVideoCodecs.available(HardwareVideoCodecs.AVC)) {
			triedAvc = true; mime = HardwareVideoCodecs.AVC; forceEncode = false;
			videoBitrate = chooseBitrate(job.queue.get(job.index)); export(); return;
		}
		if (!error && pass < 4 && length > 0) {
			forceEncode = true;
			videoBitrate = (int) Math.max(1_000, videoBitrate * (job.queue.get(job.index).target / (double) length) * 0.90);
			export(); return;
		}
		job.fail(R.string.compression_failed); finish();
	}
	private void finish() {
		if (finishing) return;
		finishing = true;
		generation++; handler.removeCallbacksAndMessages(null);
		VideoCompressionJob ending = job; job = null;
		if (ending != null) ending.changes.removeObserver(stateObserver);
		if (transformer != null) { try { transformer.cancel(); } catch (RuntimeException ignored) {} transformer = null; }
		if (output != null) { output.delete(); output = null; }
		if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (RuntimeException ignored) {} }
		if (ending != null && ending.busy()) ending.cancel();
		if (active == this) active = null;
		stopForeground(STOP_FOREGROUND_REMOVE);
		if (ending != null && !ending.cancelled && !ending.queue.isEmpty()
				&& (ending.phase == VideoCompressionJob.Phase.DONE || ending.phase == VideoCompressionJob.Phase.ERROR)) {
			try {
				NotificationCompat.Builder complete = notification().clearActions().setOngoing(false).setAutoCancel(true)
						.setProgress(0, 0, false).setContentText(getString(ending.phase == VideoCompressionJob.Phase.DONE
								? R.string.compression_finished : ending.error));
				getSystemService(NotificationManager.class).notify(NOTIFICATION, complete.build());
			} catch (RuntimeException ignored) {}
		}
		stopSelf();
	}
	@Override public void onTimeout(int startId, int fgsType) { if (job != null) job.fail(R.string.compression_failed); finish(); }
	@Override public void onDestroy() { finish(); super.onDestroy(); }
}
