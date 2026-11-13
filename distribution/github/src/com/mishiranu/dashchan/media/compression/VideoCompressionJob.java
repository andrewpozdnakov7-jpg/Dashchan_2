package com.mishiranu.dashchan.media.compression;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.MutableLiveData;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A queue transaction: no partially converted attachment can reach the send boundary. Main-thread state. */
public final class VideoCompressionJob {
	public enum Phase {PREPARING, READY, RUNNING, DONE, ERROR, CANCELLED}
	public static final class Source {
		public final String hash, name;
		public final File file;
		public long durationMs;
		public int width, height;
		public int frameRate = 30;
		public boolean video;
		public boolean hasAudio;
		public boolean forced, shrinking, hdr;
		public String videoMime, audioMime;
		public long videoBitrate;
		public long target;
		VideoInputNormalizer.Input preparedInput;
		File exportFile() { return preparedInput != null ? preparedInput.file : file; }
		public Source(String hash, String name, File file) { this.hash = hash; this.name = name; this.file = file; }
	}
	public static final class Result {
		public final String hash, name, codec;
		public final long before, after;
		public Result(String hash, String name, String codec, long before, long after) {
			this.hash = hash; this.name = name; this.codec = codec; this.before = before; this.after = after;
		}
	}
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "VideoCompressionIO"));
	public final MutableLiveData<VideoCompressionJob> changes = new MutableLiveData<>();
	public final Context context;
	public final String id = java.util.UUID.randomUUID().toString();
	final long diagnosticRun = CompressionDiagnostics.newRun();
	public final String chanName, boardName;
	public final List<Source> sources;
	public final long profile;
	public final String settings = CompressionSettings.signature(), codec = CompressionSettings.codec();
	public final long fileLimit = CompressionSettings.fileLimit(), totalLimit = CompressionSettings.totalLimit();
	public final Map<String, Result> results = new LinkedHashMap<>();
	public final List<Source> queue = new ArrayList<>();
	public Phase phase = Phase.PREPARING;
	public int percent = -1, index, attempt = 1, error;
	// Presentation only: the real current export, separate from the queue-wide notification percentage.
	public int itemPercent = -1;
	public boolean importing;
	public long totalMs, completedMs;
	public long effectiveFileLimit, effectiveTotalLimit;
	public final boolean preferHevc;
	public final boolean allowMp4;
	public final boolean always = CompressionSettings.always();
	private final java.util.Set<String> forcedVideos;
	public volatile boolean cancelled;
	private final List<String> retained = new ArrayList<>();
	private boolean released;
	private String lastDiagnostic;
	public VideoCompressionJob(Context context, List<Source> sources, long profile, String chanName, String boardName, boolean allowMp4,
			java.util.Set<String> forcedVideos) {
		this.context = context.getApplicationContext(); this.sources = sources; this.profile = profile;
		this.chanName = chanName; this.boardName = boardName;
		this.allowMp4 = allowMp4;
		this.forcedVideos = new java.util.HashSet<>(forcedVideos);
		preferHevc = chan.content.Chan.get(chanName).configuration instanceof com.mishiranu.dashchan.chan.dvach.DvachChanConfiguration;
		for (Source s : sources) retained.add(s.hash);
		DraftsStorage.getInstance().retainAttachmentDrafts(retained);
	}
	public boolean current() { return !cancelled && ProfileGeneration.canWrite(context, profile); }
	/** Rebind accepted files without starting codecs or a foreground service. Caller validates the draft. */
	public static VideoCompressionJob restoreCompleted(Context context, List<Source> sources, long profile,
			String chan, String board, boolean allowMp4, java.util.Set<String> forcedVideos, Map<String, Result> results) {
		VideoCompressionJob job = new VideoCompressionJob(context, sources, profile, chan, board, allowMp4, forcedVideos);
		for (Source source : sources) {
			Result result = results.get(source.hash);
			if (result != null && !job.results.containsKey(source.hash)) {
				job.queue.add(source); job.results.put(source.hash, result); job.retained.add(result.hash);
				DraftsStorage.getInstance().retainAttachmentDrafts(java.util.Collections.singleton(result.hash));
			}
		}
		job.index = job.queue.size(); job.percent = 100; job.phase = Phase.DONE;
		com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression restored outputs=" + job.results.size());
		return job;
	}
	public boolean isManualSource(Source source) { return forcedVideos.contains(source.hash); }
	public void changed() {
		String diagnostic = "compression phase=" + phase + " item=" + index + " count=" + queue.size() + " attempt=" + attempt + " error=" + error + " run=" + diagnosticRun;
		if (!diagnostic.equals(lastDiagnostic)) {
			com.mishiranu.dashchan.media.VideoDiagnostics.recordUi(diagnostic); lastDiagnostic = diagnostic;
		}
		changes.setValue(this);
	}
	public boolean busy() { return phase == Phase.PREPARING || phase == Phase.READY || phase == Phase.RUNNING; }
	public void prepare() {
		IO.execute(() -> {
			int failure = 0;
			int sourceIndex = -1;
			try {
				long remote = 0;
				try { remote = UploadLimitResolver.resolve(chan.content.Chan.get(chanName), boardName); }
				catch (Exception e) {
					CompressionDiagnostics.failure("limit_resolve", e);
					if (totalLimit <= 0) throw e; /* An explicit manual total can be used offline. */
				}
				effectiveTotalLimit = totalLimit > 0 ? remote > 0 ? Math.min(remote, totalLimit) : totalLimit : remote;
				effectiveFileLimit = fileLimit > 0 ? Math.min(fileLimit, effectiveTotalLimit) : effectiveTotalLimit;
				CompressionDiagnostics.limits(diagnosticRun, remote, effectiveFileLimit, effectiveTotalLimit);
				if (effectiveTotalLimit <= 0 || effectiveFileLimit <= 0) {
					MAIN.post(() -> { if (!cancelled) fail(R.string.compression_limits_unknown); }); return;
				}
				long[] sizes = new long[sources.size()]; boolean[] videos = new boolean[sources.size()], force = new boolean[sources.size()];
				for (int i = 0; i < sources.size(); i++) {
					sourceIndex = i;
					if (cancelled) return;
					Source s = sources.get(i); sizes[i] = s.file.length();
					if (!chan.content.Chan.getFallback().locator.isVideoExtension(s.name)) continue;
					try (MediaMetadataRetriever metadata = new MediaMetadataRetriever()) {
						metadata.setDataSource(s.file.getPath());
						s.video = "yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
						if (!s.video) throw new java.io.IOException("No readable video track");
						s.hasAudio = "yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
						if (s.video) {
							s.durationMs = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
							s.width = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
							s.height = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
							String rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
							if ("90".equals(rotation) || "270".equals(rotation)) { int swap = s.width; s.width = s.height; s.height = swap; }
							if (s.durationMs <= 0 || s.width <= 0 || s.height <= 0) throw new IllegalArgumentException();
							videos[i] = true;
							force[i] = s.forced = always || forcedVideos.contains(s.hash);
							s.videoBitrate = Math.max(1, (long) (sizes[i] * 8000.0 / s.durationMs));
						}
					}
					if (s.video) {
						android.media.MediaExtractor extractor = new android.media.MediaExtractor();
						try {
							extractor.setDataSource(s.file.getPath());
							for (int track = 0; track < extractor.getTrackCount(); track++) {
								android.media.MediaFormat format = extractor.getTrackFormat(track);
								CompressionDiagnostics.sourceTrack(diagnosticRun, i, track, format);
								String mime = format.getString(android.media.MediaFormat.KEY_MIME);
								if (mime != null && mime.startsWith("video/") && s.videoMime == null) {
									s.videoMime = mime;
									int transfer = format.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER, android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
									s.hdr = transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 || transfer == android.media.MediaFormat.COLOR_TRANSFER_HLG;
									if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) s.frameRate = Math.max(1, format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE));
									if (format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
										int bitrate = format.getInteger(android.media.MediaFormat.KEY_BIT_RATE);
										if (bitrate > 0) s.videoBitrate = bitrate;
									}
								} else if (mime != null && mime.startsWith("audio/")) {
									s.audioMime = mime;
								}
							}
							CompressionDiagnostics.inspectSource(diagnosticRun, i, s.file, extractor);
						} catch (Exception e) { CompressionDiagnostics.failure("source_extract", e); } finally { extractor.release(); }
					}
					CompressionDiagnostics.preparation(diagnosticRun, i, s);
				}
				long[] targets = CompressionBudget.allocateForConversion(sizes, videos, force, effectiveFileLimit, effectiveTotalLimit);
				if (targets == null) failure = R.string.compression_cannot_fit;
				else for (int i = 0; i < sources.size(); i++) {
					Source s = sources.get(i); s.target = targets[i];
					s.shrinking = s.target < sizes[i];
					if (videos[i] && (s.shrinking || s.forced)) {
						queue.add(s); totalMs = Math.addExact(totalMs, s.durationMs);
					}
				}
				if (!allowMp4 && !queue.isEmpty()) failure = R.string.compression_format_unsupported;
				if (failure == 0) for (Source source : queue) {
					sourceIndex = sources.indexOf(source);
					VideoInputNormalizer.Input input = VideoInputNormalizer.prepare(source.file,
							new File(context.getCacheDir(), "video-compression-inputs"), () -> cancelled);
					synchronized (this) {
						if (released || cancelled) { input.close(); return; }
						source.preparedInput = input;
					}
					com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression input_normalization run=" + diagnosticRun
							+ " source=" + sourceIndex + " copied=" + input.temporary + " reason=" + input.inspection.reason
							+ " original_bytes=" + input.inspection.originalBytes + " readable_bytes=" + input.inspection.readableBytes
							+ " removed_bytes=" + (input.inspection.originalBytes - input.inspection.readableBytes));
				}
			} catch (Exception e) {
				com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression prepare_failed source=" + sourceIndex + " run=" + diagnosticRun);
				CompressionDiagnostics.failure("prepare", e); failure = R.string.compression_failed;
			}
			final int finalFailure = failure;
			MAIN.post(() -> {
				if (!current()) { cancel(); return; }
				if (finalFailure != 0) fail(finalFailure);
				else { phase = queue.isEmpty() ? Phase.DONE : Phase.READY; changed(); }
			});
		});
	}
	public void importResult(Source source, File output, String usedCodec, Runnable next) {
		importing = true; changed();
		com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression import_started item=" + index + " run=" + diagnosticRun);
		IO.execute(() -> {
			String hash = null; long length = output.length();
			try { if (!cancelled && length > 0 && length <= source.target && length <= Integer.MAX_VALUE)
				hash = DraftsStorage.getInstance().storeAttachmentFile(FileHolder.obtain(output)); }
			catch (RuntimeException e) { CompressionDiagnostics.failure("import", e); }
			finally { output.delete(); }
			final String imported = hash;
			MAIN.post(() -> {
				com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression import_finished accepted=" + (imported != null)
						+ " bytes=" + length + " item=" + index + " run=" + diagnosticRun);
				if (imported != null) {
					DraftsStorage.getInstance().retainAttachmentDrafts(java.util.Collections.singleton(imported));
					if (released || !current()) {
						DraftsStorage.getInstance().releaseAttachmentDrafts(java.util.Collections.singleton(imported));
						cancel(); return;
					}
					retained.add(imported);
					String name = source.name; int dot = name.lastIndexOf('.');
					results.put(source.hash, new Result(imported, (dot > 0 ? name.substring(0, dot) : name) + ".mp4",
							usedCodec, source.file.length(), length));
					completedMs += source.durationMs; index++; next.run();
				} else { fail(R.string.compression_failed); next.run(); }
			});
		});
	}
	public void reuseOriginal(Source source, String usedCodec) {
		com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression unchanged reason=codec_matches item=" + index + " run=" + diagnosticRun);
		results.put(source.hash, new Result(source.hash, source.name, usedCodec, source.file.length(), source.file.length()));
		completedMs += source.durationMs; index++;
	}
	public void fail(int message) {
		String reason;
		try { reason = context.getResources().getResourceEntryName(message); }
		catch (android.content.res.Resources.NotFoundException e) { reason = "unknown"; }
		com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("compression failed reason=" + reason + " item=" + index + " run=" + diagnosticRun);
		error = message; phase = Phase.ERROR; results.clear(); release(); changed();
	}
	public void cancel() { cancelled = true; phase = Phase.CANCELLED; results.clear(); release(); changed(); }
	synchronized void releasePreparedInputs() {
		for (Source source : sources) {
			if (source.preparedInput != null) { source.preparedInput.close(); source.preparedInput = null; }
		}
	}
	public synchronized void release() {
		releasePreparedInputs();
		if (!released) { released = true; DraftsStorage.getInstance().releaseAttachmentDrafts(retained); retained.clear(); }
	}
}
