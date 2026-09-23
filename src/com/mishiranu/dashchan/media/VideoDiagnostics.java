package com.mishiranu.dashchan.media;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class VideoDiagnostics {
	private static final String DIRECTORY_NAME = "video-diagnostics";
	private static final String FILE_PREFIX = "video-diagnostics-";
	private static final int MAX_FILES = 3;
	private static final int UI_REPORT_LIMIT = 64 * 1024;
	// UTF-16 storage plus the export snapshot stay within roughly 4 MiB.
	private static final int EXTENDED_UI_REPORT_LIMIT = 512 * 1024;
	private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "VideoDiagnostics");
		thread.setDaemon(true);
		return thread;
	});

	private static final Object LOCK = new Object();
	private static volatile boolean recording;
	private static volatile boolean extended;
	private static boolean saving;
	private static boolean samplingUnavailable;
	private static ScheduledFuture<?> sampler;
	private static long startedAt;
	private static long startedElapsedRealtime;
	private static StringBuilder uiReport = new StringBuilder();
	private static boolean uiReportTruncated;
	private static File lastFile;
	private static final LinkedHashMap<Integer, SurfaceStats> surfaces = new LinkedHashMap<>();
	private static class SurfaceStats {
		long updates, sampledUpdates, updatedAt;
	}

	private VideoDiagnostics() {}

	public static boolean isRecording() {
		synchronized (LOCK) {
			return recording;
		}
	}

	public static boolean isExtendedRecording() {
		synchronized (LOCK) { return recording && extended; }
	}

	public static boolean isSaving() {
		synchronized (LOCK) { return saving; }
	}

	public static synchronized boolean start() {
		synchronized (LOCK) {
			if (recording || saving) {
				return false;
			}
			recording = true;
			extended = Preferences.PREFERENCES.getBoolean(Preferences.KEY_EXTENDED_VIDEO_DIAGNOSTICS, false);
			samplingUnavailable = false;
			startedAt = System.currentTimeMillis();
			startedElapsedRealtime = SystemClock.elapsedRealtime();
			uiReport.setLength(0);
			uiReportTruncated = false;
			appendUiReportLocked("capture_started=true");
			appendUiReportLocked("mode=" + (extended ? "extended" : "standard"));
			for (SurfaceStats surface : surfaces.values()) {
				surface.sampledUpdates = surface.updates;
				surface.updatedAt = 0;
			}
		}
		VideoPlayer.startDiagnosticCapture();
		if (extended) sampler = WORKER.scheduleWithFixedDelay(VideoDiagnostics::sample, 1, 1, TimeUnit.SECONDS);
		return true;
	}

	public static void stopAsync(Consumer<File> callback) {
		synchronized (LOCK) {
			if (saving) return;
			saving = true;
		}
		WORKER.execute(() -> {
			File file = stop();
			if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.accept(file));
		});
	}

	public static synchronized File stop() {
		long sessionStartedAt;
		String uiReport;
		synchronized (LOCK) {
			if (!recording) {
				saving = false;
				return getLastFileLocked();
			}
			appendUiReportLocked("capture_stopped=true");
			recording = false;
			saving = true;
			if (sampler != null) { sampler.cancel(false); sampler = null; }
			sessionStartedAt = startedAt;
			startedAt = 0L;
			startedElapsedRealtime = 0L;
			uiReport = VideoDiagnostics.uiReport.toString();
		}
		try {
			int nativeLength = VideoPlayer.finishDiagnosticCapture();
			String nativeReport = nativeLength < 0 ? VideoPlayer.stopDiagnosticCapture() : null;
			File file = writeReport(sessionStartedAt, System.currentTimeMillis(), nativeLength, nativeReport, uiReport);
			synchronized (LOCK) {
				if (file != null) lastFile = file;
			}
			return file;
		} finally {
			VideoPlayer.releaseDiagnosticCapture();
			synchronized (LOCK) { saving = false; VideoDiagnostics.uiReport = new StringBuilder(); }
		}
	}

	// Only aggregate numbers are kept. No View, Surface, URL, or media object is retained here.
	static void surfaceAvailable(int id) {
		synchronized (LOCK) {
			if (surfaces.size() >= 16) surfaces.remove(surfaces.keySet().iterator().next());
			surfaces.put(id, new SurfaceStats());
		}
	}

	static void surfaceDestroyed(int id) {
		synchronized (LOCK) { surfaces.remove(id); }
	}

	static void surfaceUpdated(int id) {
		if (!recording || !extended) return;
		synchronized (LOCK) {
			SurfaceStats surface = surfaces.get(id);
			if (surface != null) {
				surface.updates++;
				if (recording && extended) surface.updatedAt = SystemClock.elapsedRealtime();
			}
		}
	}

	private static void sample() {
		if (!isExtendedRecording()) return;
		boolean available = VideoPlayer.sampleDiagnosticCapture();
		synchronized (LOCK) {
			if (!recording || !extended) return;
			if (!available && !samplingUnavailable) appendUiReportLocked("extended_native_sampling_unavailable=true");
			samplingUnavailable = !available;
			long now = SystemClock.elapsedRealtime();
			for (Map.Entry<Integer, SurfaceStats> entry : surfaces.entrySet()) {
				SurfaceStats surface = entry.getValue();
				appendUiReportLocked("texture=" + entry.getKey() + " sample updates="
						+ (surface.updates - surface.sampledUpdates) + " total=" + surface.updates
						+ " last_update_age_ms=" + (surface.updatedAt > 0 ? now - surface.updatedAt : -1));
				surface.sampledUpdates = surface.updates;
			}
			Runtime runtime = Runtime.getRuntime();
			appendUiReportLocked("java_heap used_bytes=" + (runtime.totalMemory() - runtime.freeMemory())
					+ " max_bytes=" + runtime.maxMemory());
		}
	}

	public static void recordUi(String message) {
		synchronized (LOCK) {
			if (recording) {
				appendUiReportLocked(message);
			}
		}
	}

	// Permanent extended-video diagnostics. Read geometry only; never mutate layout or retain Views.
	// Call on the UI thread at lifecycle/layout boundaries, not for every decoded frame.
	public static void recordViewGeometry(String event, View target) {
		if (!isExtendedRecording() || target == null || Looper.myLooper() != Looper.getMainLooper()) return;
		try {
			View view = target;
			for (int depth = 0; view != null && depth < 6; depth++) {
				int[] screen = new int[2];
				int[] window = new int[2];
				view.getLocationOnScreen(screen);
				view.getLocationInWindow(window);
				Rect global = new Rect();
				Rect local = new Rect();
				Rect visibleFrame = new Rect();
				boolean globalVisible = view.getGlobalVisibleRect(global);
				boolean localVisible = view.getLocalVisibleRect(local);
				view.getWindowVisibleDisplayFrame(visibleFrame);
				float[] matrix = new float[9];
				view.getMatrix().getValues(matrix);
				StringBuilder line = new StringBuilder("view_geometry schema=1 event=").append(event)
						.append(" target=").append(System.identityHashCode(target))
						.append(" depth=").append(depth).append(" view=").append(System.identityHashCode(view))
						.append(" class=").append(view.getClass().getSimpleName())
						.append(" bounds=").append(view.getLeft()).append(',').append(view.getTop())
						.append(',').append(view.getRight()).append(',').append(view.getBottom())
						.append(" measured=").append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight())
						.append(" screen=").append(Arrays.toString(screen)).append(" window=").append(Arrays.toString(window))
						.append(" translation=").append(view.getTranslationX()).append(',').append(view.getTranslationY())
						.append(" scale=").append(view.getScaleX()).append(',').append(view.getScaleY())
						.append(" pivot=").append(view.getPivotX()).append(',').append(view.getPivotY())
						.append(" rotation=").append(view.getRotation()).append(',').append(view.getRotationX())
						.append(',').append(view.getRotationY()).append(" z=").append(view.getZ())
						.append(" matrix=").append(Arrays.toString(matrix))
						.append(" scroll=").append(view.getScrollX()).append(',').append(view.getScrollY())
						.append(" clip=").append(view.getClipBounds()).append(" clipOutline=").append(view.getClipToOutline())
						.append(" globalVisible=").append(globalVisible).append(':').append(global.toShortString())
						.append(" localVisible=").append(localVisible).append(':').append(local.toShortString())
						.append(" windowFrame=").append(visibleFrame.toShortString())
						.append(" visibility=").append(view.getVisibility()).append(" windowVisibility=").append(view.getWindowVisibility())
						.append(" shown=").append(view.isShown()).append(" alpha=").append(view.getAlpha())
						.append(" attached=").append(view.isAttachedToWindow()).append(" layoutRequested=").append(view.isLayoutRequested())
						.append(" hardware=").append(view.isHardwareAccelerated()).append(" layer=").append(view.getLayerType())
						.append(" displayRotation=").append(view.getDisplay() != null ? view.getDisplay().getRotation() : -1);
				ViewGroup.LayoutParams params = view.getLayoutParams();
				if (params != null) line.append(" layoutSize=").append(params.width).append('x').append(params.height);
				if (view instanceof ViewGroup) {
					ViewGroup group = (ViewGroup) view;
					line.append(" clipChildren=").append(group.getClipChildren())
							.append(" clipPadding=").append(group.getClipToPadding()).append(" children=").append(group.getChildCount());
				}
				if (view instanceof TextureView) {
					TextureView texture = (TextureView) view;
					Matrix transform = texture.getTransform(new Matrix());
					transform.getValues(matrix);
					RectF mapped = new RectF(0, 0, view.getWidth(), view.getHeight());
					transform.mapRect(mapped);
					line.append(" textureAvailable=").append(texture.isAvailable()).append(" opaque=").append(texture.isOpaque())
							.append(" textureTransform=").append(Arrays.toString(matrix)).append(" textureMapped=").append(mapped);
				}
				recordUi(line.toString());
				ViewParent parent = view.getParent();
				view = parent instanceof View ? (View) parent : null;
			}
		} catch (RuntimeException e) {
			recordUi("view_geometry failed=" + e.getClass().getSimpleName());
		}
	}

	private static void appendUiReportLocked(String message) {
		long elapsed = startedElapsedRealtime > 0L
				? Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedRealtime) : 0L;
		String line = "[+" + elapsed + "ms] " + safeLine(message) + "\n";
		int limit = extended ? EXTENDED_UI_REPORT_LIMIT : UI_REPORT_LIMIT;
		if (line.length() > limit / 2) line = line.substring(0, limit / 2) + " [line_truncated]\n";
		if (uiReport.length() + line.length() > limit) {
			int keep = uiReport.indexOf("\n", 4096) + 1;
			int end = uiReport.indexOf("\n", Math.max(keep, uiReport.length() + line.length() - limit + limit / 4));
			uiReport.delete(keep, end >= 0 ? end + 1 : uiReport.length());
			uiReport.insert(keep, "[older_ui_events_evicted]\n");
			uiReportTruncated = true;
		}
		uiReport.append(line);
	}

	public static File getLastFile() {
		synchronized (LOCK) {
			return getLastFileLocked();
		}
	}

	public static boolean deleteLastFile() {
		synchronized (LOCK) {
			File file = getLastFileLocked();
			if (file == null) {
				return true;
			}
			boolean deleted = file.delete();
			if (deleted) {
				lastFile = null;
			}
			return deleted;
		}
	}

	private static File getLastFileLocked() {
		if (lastFile != null && lastFile.isFile()) {
			return lastFile;
		}
		File directory = getDirectory();
		File[] files = directory != null ? directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(".txt")) : null;
		if (files == null || files.length == 0) {
			return null;
		}
		Arrays.sort(files, (first, second) ->
				Long.compare(second.lastModified(), first.lastModified()));
		lastFile = files[0];
		return lastFile;
	}

	private static File writeReport(long startedAt, long stoppedAt, int nativeLength, String nativeReport, String uiReport) {
		File directory = getDirectory();
		if (directory == null || !(directory.isDirectory() || directory.mkdirs())) {
			return null;
		}
		File file = new File(directory, FILE_PREFIX + formatTime(startedAt, "yyyyMMdd-HHmmss-SSS") + ".txt");
		try (FileOutputStream output = new FileOutputStream(file);
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
			writer.write("Dashchan video diagnostics\n");
			writer.write("schema=3\n");
			writer.write("mode=" + (extended ? "extended" : "standard") + "\n");
			writer.write("started_utc=" + formatTime(startedAt, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") + "\n");
			writer.write("stopped_utc=" + formatTime(stoppedAt, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") + "\n");
			writer.write("duration_ms=" + Math.max(0L, stoppedAt - startedAt) + "\n");
			writer.write("app_version=" + safe(BuildConfig.VERSION_NAME) + "\n");
			writer.write("app_version_code=" + BuildConfig.VERSION_CODE + "\n");
			writer.write("player_build=" + safe(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR) + "\n");
			writer.write("player_abis=" + join(BuildConfig.NATIVE_PLAYER_ABIS) + "\n");
			writer.write("android_api=" + Build.VERSION.SDK_INT + "\n");
			writer.write("android_release=" + safe(Build.VERSION.RELEASE) + "\n");
			writer.write("manufacturer=" + safe(Build.MANUFACTURER) + "\n");
			writer.write("model=" + safe(Build.MODEL) + "\n");
			writer.write("device_abis=" + join(Build.SUPPORTED_ABIS) + "\n");
			writer.write("available_video_decoders=" + getAvailableVideoDecoders() + "\n");
			writer.write("privacy=no_urls,no_post_content,no_media_bytes,no_file_names,no_accounts,no_device_ids\n");
			writer.write("\n[native_player]\n");
			if (nativeLength > 0) {
				writer.flush();
				for (int offset = 0; offset < nativeLength;) {
					byte[] chunk = VideoPlayer.readDiagnosticChunk(offset, Math.min(64 * 1024, nativeLength - offset));
					if (chunk == null || chunk.length == 0) throw new IOException("Incomplete diagnostic capture");
					output.write(chunk);
					offset += chunk.length;
				}
			} else if (nativeReport != null && !nativeReport.isEmpty()) {
				writer.write(nativeReport);
				if (nativeReport.charAt(nativeReport.length() - 1) != '\n') {
					writer.write('\n');
				}
			} else {
				writer.write("native_player_not_loaded=true\n");
			}
			writer.write("\n[ui_player]\n");
			writer.write("older_events_evicted=" + uiReportTruncated + "\n");
			if (uiReport != null && !uiReport.isEmpty()) {
				writer.write(uiReport);
				if (uiReport.charAt(uiReport.length() - 1) != '\n') {
					writer.write('\n');
				}
			} else {
				writer.write("ui_player_not_recorded=true\n");
			}
		} catch (IOException | RuntimeException | LinkageError e) {
			file.delete();
			return null;
		}
		cleanupOldFiles(directory);
		return file;
	}

	private static File getDirectory() {
		File cacheDirectory = MainApplication.getInstance().getCacheDir();
		return cacheDirectory != null ? new File(cacheDirectory, DIRECTORY_NAME) : null;
	}

	private static void cleanupOldFiles(File directory) {
		File[] files = directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(".txt"));
		if (files == null || files.length <= MAX_FILES) {
			return;
		}
		Arrays.sort(files, (first, second) ->
				Long.compare(second.lastModified(), first.lastModified()));
		for (int i = MAX_FILES; i < files.length; i++) {
			files[i].delete();
		}
	}

	private static String getAvailableVideoDecoders() {
		StringBuilder builder = new StringBuilder();
		try {
			for (MediaCodecInfo codecInfo : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
				if (codecInfo.isEncoder()) {
					continue;
				}
				boolean supported = false;
				for (String type : codecInfo.getSupportedTypes()) {
					if (VideoDecoderCapabilities.isSupportedMimeType(type)) {
						supported = true;
						break;
					}
				}
				if (supported) {
					if (builder.length() > 0) {
						builder.append(',');
					}
					builder.append(safe(codecInfo.getName()));
					builder.append(codecInfo.isHardwareAccelerated() ? "[hw]" : "[sw]");
				}
			}
		} catch (RuntimeException ignored) {
			return "unavailable";
		}
		return builder.length() > 0 ? builder.toString() : "none";
	}

	private static String formatTime(long time, String pattern) {
		SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		return dateFormat.format(new Date(time));
	}

	private static String join(String[] values) {
		StringBuilder builder = new StringBuilder();
		for (String value : values) {
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(safe(value));
		}
		return builder.toString();
	}

	private static String safe(String value) {
		return value != null ? value.replace('\n', ' ').replace('\r', ' ').replace(',', '_') : "";
	}

	private static String safeLine(String value) {
		return value != null ? value.replace('\n', ' ').replace('\r', ' ') : "";
	}
}
