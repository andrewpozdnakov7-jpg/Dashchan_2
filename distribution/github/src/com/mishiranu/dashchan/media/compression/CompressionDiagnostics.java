package com.mishiranu.dashchan.media.compression;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.metrics.LogSessionId;
import android.os.SystemClock;
import android.system.ErrnoException;
import androidx.media3.common.Format;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional metadata-only export tracing. No media bytes, raw exception messages, paths or Format.toString(). */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class CompressionDiagnostics {
	private static final AtomicLong NEXT_RUN = new AtomicLong();
	private static volatile Session active;
	private static final Pattern TIME = Pattern.compile("^(?:-?\\d{1,19}us|UNSET|EOS)@-?\\d{1,19}ms");
	private static final Pattern WATCHDOG = Pattern.compile("Abort: no output sample written in the last (\\d{1,10}) milliseconds\\.");
	private static final java.util.Set<String> STAGES = new java.util.HashSet<>(java.util.Arrays.asList(
			"CompositionPlayer", "TransformerInternal", "AssetLoader", "AudioDecoder", "AudioGraph", "AudioGraphInput",
			"AGIAudioSink", "AudioMixer", "AudioEncoder", "VideoDecoder", "VideoFrameProcessor", "ExternalTextureManager",
			"BitmapTextureManager", "TexIdTextureManager", "Compositor", "VideoEncoder", "Muxer"));
	private static final java.util.Set<String> EVENTS = new java.util.HashSet<>(java.util.Arrays.asList(
			"SetComposition", "SeekTo", "SetVideoOutput", "Release", "Reset", "Start", "InputFormat", "OutputFormat",
			"AcceptedInput", "BlockInput", "UnblockInput", "ProducedOutput", "InputEnded", "OutputEnded", "RegisterNewInputStream",
			"SurfaceTextureInput", "SurfaceTextureTransformFix", "QueueFrame", "QueueBitmap", "QueueTexture", "OutputTextureRendered",
			"RenderedToOutputSurface", "ReceiveEndOfAllInput", "SignalEOS", "SignalEnded", "CanWriteSample", "Flush", "Discontinuity"));
	private CompressionDiagnostics() {}
	public static long newRun() { return NEXT_RUN.incrementAndGet(); }
	public static void captureStarted() { Session session = active; if (session != null) session.syncTrace(); }
	public static void captureStopping() {
		Session session = active;
		if (session != null) {
			session.snapshot("capture_stop", true);
			long capture = VideoDiagnostics.getExtendedCaptureId();
			synchronized (DebugTraceUtil.class) {
				if (active != session) return;
				session.blockedCapture = capture;
				session.releaseTraceLocked();
			}
		}
	}
	static String token(String value) {
		return value == null ? "unknown" : value.length() <= 180 && value.matches("[A-Za-z0-9_.$:+-]+") ? value : "redacted";
	}
	static String mime(String value) {
		return value != null && value.length() <= 80 && value.matches("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+") ? value : "unknown";
	}
	private static void detail(long capture, String text) {
		if (capture != 0) VideoDiagnostics.recordExtendedUi(capture, "compression_detail " + text);
	}
	static void failure(String stage, Throwable failure) {
		long capture = VideoDiagnostics.getExtendedCaptureId();
		if (capture == 0 || failure == null) return;
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 8; depth++) {
			detail(capture, "exception stage=" + token(stage) + " depth=" + depth + " type=" + token(current.getClass().getName()));
			if (current instanceof ExportException) {
				ExportException export = (ExportException) current;
				detail(capture, "exception export_code=" + export.errorCode + " export_name=" + token(export.getErrorCodeName()));
				if (export.codecInfo != null) detail(capture, "exception codec=" + token(export.codecInfo.name)
						+ " video=" + export.codecInfo.isVideo + " decoder=" + export.codecInfo.isDecoder);
			}
			if (current instanceof MediaCodec.CodecException) {
				MediaCodec.CodecException codec = (MediaCodec.CodecException) current;
				detail(capture, "exception codec_error=" + codec.getErrorCode() + " recoverable=" + codec.isRecoverable()
						+ " transient=" + codec.isTransient() + " diagnostic=" + token(codec.getDiagnosticInfo()));
			}
			if (current instanceof ErrnoException) detail(capture, "exception errno=" + ((ErrnoException) current).errno);
			// Extract only the fixed watchdog duration. The rest may contain paths or raw trace extras.
			String message = current.getMessage();
			Matcher watchdog = message != null ? WATCHDOG.matcher(message) : null;
			if (watchdog != null && watchdog.find()) detail(capture, "exception watchdog_ms=" + watchdog.group(1));
			StackTraceElement[] stack = current.getStackTrace();
			StringBuilder frames = new StringBuilder("exception stack_depth=").append(depth);
			for (int i = 0; i < Math.min(16, stack.length); i++) frames.append(' ').append(token(stack[i].getClassName()))
					.append('.').append(token(stack[i].getMethodName())).append(':').append(stack[i].getLineNumber());
			detail(capture, frames.toString());
			Throwable[] suppressed = current.getSuppressed();
			for (int i = 0; i < Math.min(4, suppressed.length); i++) detail(capture,
					"exception suppressed_depth=" + depth + " type=" + token(suppressed[i].getClass().getName()));
			Throwable next = current.getCause(); if (next == current) break; current = next;
		}
	}
	static void preparation(long run, int index, VideoCompressionJob.Source source) {
		if (!VideoDiagnostics.isExtendedRecording()) return;
		detail(VideoDiagnostics.getExtendedCaptureId(), "run=" + run + " source=" + index + " prepared bytes=" + source.file.length()
				+ " duration_ms=" + source.durationMs + " width=" + source.width + " height=" + source.height
				+ " fps=" + source.frameRate + " video=" + source.video + " audio=" + source.hasAudio
				+ " video_mime=" + mime(source.videoMime) + " audio_mime=" + mime(source.audioMime)
				+ " hdr=" + source.hdr + " video_bitrate=" + source.videoBitrate);
	}
	static void limits(long run, long remote, long file, long total) {
		detail(VideoDiagnostics.getExtendedCaptureId(), "run=" + run + " limits remote_bytes=" + remote
				+ " effective_file_bytes=" + file + " effective_total_bytes=" + total);
	}
	static Session begin(VideoCompressionJob job, int attempt, String requestedMime, boolean copyVideo,
			int width, int height, int bitrate) {
		VideoCompressionJob.Source source = job.queue.get(job.index);
		Session result = new Session(job.diagnosticRun, attempt, job.index,
				"requested=" + mime(requestedMime) + " source_video=" + mime(source.videoMime) + " source_audio=" + mime(source.audioMime)
				+ " source_bytes=" + source.file.length() + " duration_ms=" + source.durationMs + " source_width=" + source.width
				+ " source_height=" + source.height + " source_fps=" + source.frameRate + " source_hdr=" + source.hdr
				+ " has_audio=" + source.hasAudio + " manual=" + job.isManualSource(source) + " always=" + job.always
				+ " shrinking=" + source.shrinking + " copy_video=" + copyVideo + " requested_width=" + width + " requested_height=" + height
				+ " requested_video_bitrate=" + bitrate + " configured_codec=" + token(job.codec)
				+ " target_bytes=" + source.target + " total_limit=" + job.effectiveTotalLimit + " file_limit=" + job.effectiveFileLimit);
		result.candidates(requestedMime, width, height, source.frameRate);
		return result;
	}
	static String format(Format f) {
		if (f == null) return "format=unknown";
		return "mime=" + mime(f.sampleMimeType) + " container=" + mime(f.containerMimeType)
				+ " width=" + f.width + " height=" + f.height + " fps=" + f.frameRate + " rotation=" + f.rotationDegrees
				+ " average_bitrate=" + f.averageBitrate + " peak_bitrate=" + f.peakBitrate
				+ " sample_rate=" + f.sampleRate + " channels=" + f.channelCount + " pcm=" + f.pcmEncoding
				+ " init_blocks=" + f.initializationData.size() + " drm=" + (f.drmInitData != null)
				+ (f.colorInfo != null ? " color_space=" + f.colorInfo.colorSpace + " color_range=" + f.colorInfo.colorRange
						+ " color_transfer=" + f.colorInfo.colorTransfer : "");
	}
	static void sourceTrack(long run, int source, int index, MediaFormat format) {
		long capture = VideoDiagnostics.getExtendedCaptureId(); if (capture == 0) return;
		try {
			StringBuilder text = new StringBuilder("run=").append(run).append(" source=").append(source)
					.append(" source_track index=").append(index).append(" mime=").append(mime(format.getString(MediaFormat.KEY_MIME)));
			for (String key : new String[] {"width", "height", "frame-rate", "rotation-degrees", "durationUs", "bitrate",
					"sample-rate", "channel-count", "aac-profile", "profile", "level", "color-standard", "color-range", "color-transfer", "max-input-size"}) {
				try { if (format.containsKey(key)) text.append(' ').append(key).append('=').append(format.getNumber(key)); }
				catch (RuntimeException ignored) { text.append(' ').append(key).append("=unavailable"); }
			}
			for (int i = 0; i < 3; i++) {
				java.nio.ByteBuffer init = format.getByteBuffer("csd-" + i);
				if (init != null) text.append(" csd_").append(i).append("_bytes=").append(init.remaining());
			}
			detail(capture, text.toString());
		} catch (RuntimeException e) { detail(capture, "source_track unavailable=" + token(e.getClass().getSimpleName())); }
	}
	/** Uses the already-open metadata extractor on the preparation worker. Reads no sample payload. */
	static void inspectSource(long run, int sourceIndex, File file, MediaExtractor extractor) {
		long capture = VideoDiagnostics.getExtendedCaptureId(); if (capture == 0) return;
		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			long offset = 0, length = input.length(); int count = 0, moof = 0, mdat = 0;
			StringBuilder boxes = new StringBuilder(); boolean valid = true;
			int firstType = 0;
			if (length >= 8) { input.seek(4); firstType = input.readInt(); }
			boolean iso = firstType == 0x66747970 || firstType == 0x6d6f6f76 || firstType == 0x6d646174
					|| firstType == 0x66726565 || firstType == 0x77696465 || firstType == 0x736b6970 || firstType == 0x73747970;
			while (iso && offset + 8 <= length && count < 512) {
				input.seek(offset); long size = input.readInt() & 0xffffffffL; int type = input.readInt(), header = 8;
				if (size == 1) { if (offset + 16 > length) { valid = false; break; } size = input.readLong(); header = 16; }
				if (size == 0) size = length - offset;
				if (size < header || size > length - offset) { valid = false; break; }
				String kind;
				switch (type) {
					case 0x66747970: kind = "ftyp"; break;
					case 0x6d6f6f76: kind = "moov"; break;
					case 0x6d6f6f66: kind = "moof"; moof++; break;
					case 0x6d646174: kind = "mdat"; mdat++; break;
					case 0x73696478: kind = "sidx"; break;
					case 0x66726565: kind = "free"; break;
					default: kind = "other";
				}
				if (count < 24) boxes.append(kind).append(':').append(size).append(',');
				offset += size; count++;
			}
			detail(capture, "run=" + run + " source=" + sourceIndex + " container_probe file_bytes=" + length + " boxes_scanned=" + count + " moof=" + moof
					+ " mdat=" + mdat + " iso_bmff=" + iso + " fragmented=" + (iso ? Boolean.toString(moof > 0) : "unavailable")
					+ " valid_box_layout=" + (iso ? Boolean.toString(valid) : "unavailable")
					+ " scan_complete=" + (offset == length) + " trailing_bytes=" + (length - offset) + " first_boxes=" + boxes);
		} catch (Exception e) { detail(capture, "container_probe unavailable=" + token(e.getClass().getSimpleName())); }
		try {
			int tracks = Math.min(16, extractor.getTrackCount());
			long[] first = new long[tracks], last = new long[tracks], maxGap = new long[tracks];
			int[] counts = new int[tracks], backwards = new int[tracks];
			java.util.Arrays.fill(first, -1); java.util.Arrays.fill(last, -1);
			for (int i = 0; i < tracks; i++) extractor.selectTrack(i);
			long deadline = SystemClock.elapsedRealtime() + 250; int samples = 0, runs = 0, previousTrack = -1;
			while (samples < 256 && SystemClock.elapsedRealtime() < deadline) {
				int track = extractor.getSampleTrackIndex(); long time = extractor.getSampleTime();
				if (track < 0 || track >= tracks || time < 0) break;
				if (track != previousTrack) { runs++; previousTrack = track; }
				if (counts[track]++ == 0) first[track] = time;
				else { maxGap[track] = Math.max(maxGap[track], time - last[track]); if (time < last[track]) backwards[track]++; }
				last[track] = time; samples++; if (!extractor.advance()) break;
			}
			detail(capture, "run=" + run + " source=" + sourceIndex + " sample_probe samples=" + samples + " track_runs=" + runs + " limit_samples=256 budget_ms=250 payload_read=false");
			for (int i = 0; i < tracks; i++) detail(capture, "run=" + run + " source=" + sourceIndex + " sample_track index=" + i + " samples=" + counts[i]
					+ " first_us=" + first[i] + " last_us=" + last[i] + " max_forward_gap_us=" + maxGap[i] + " pts_backwards=" + backwards[i]);
		} catch (RuntimeException e) { detail(capture, "sample_probe unavailable=" + token(e.getClass().getSimpleName())); }
	}

	static final class Session {
		private final long run, started = SystemClock.elapsedRealtime();
		private final int attempt, item;
		private final String context;
		private volatile boolean closed;
		// Guarded by DebugTraceUtil.class; never call recordUi while holding that monitor.
		private long traceCapture, blockedCapture;
		private boolean previousTracing;
		private long lastSnapshot;
		Session(long run, int attempt, int item, String context) {
			this.run = run; this.attempt = attempt; this.item = item; this.context = context;
			Session previous = active; if (previous != null) previous.close("replaced_attempt");
			active = this; syncTrace();
		}
		private String prefix() { return "run=" + run + " item=" + item + " attempt=" + attempt + ' '; }
		void detail(String text) { CompressionDiagnostics.detail(VideoDiagnostics.getExtendedCaptureId(), prefix() + text); }
		void failure(String stage, Throwable error) { detail("failure stage=" + token(stage)); CompressionDiagnostics.failure(stage, error); }
		void candidates(String mime, int width, int height, int fps) {
			if (!VideoDiagnostics.isExtendedRecording()) return;
			int index = 0;
			for (MediaCodecInfo info : HardwareVideoCodecs.find(mime, true)) {
				if (index++ >= 8) break;
				try {
					MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
					MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
					detail("encoder_candidate name=" + token(info.getName()) + " hardware=" + info.isHardwareAccelerated()
							+ " software=" + info.isSoftwareOnly() + " width_alignment=" + video.getWidthAlignment()
							+ " height_alignment=" + video.getHeightAlignment() + " supported_size=" + video.isSizeSupported(width, height)
							+ " supported_size_rate=" + video.areSizeAndRateSupported(width, height, fps)
							+ " bitrate_min=" + video.getBitrateRange().getLower() + " bitrate_max=" + video.getBitrateRange().getUpper());
					StringBuilder profiles = new StringBuilder("encoder_profiles name=").append(token(info.getName()));
					for (int i = 0; i < Math.min(24, caps.profileLevels.length); i++) profiles.append(' ').append(caps.profileLevels[i].profile)
							.append(':').append(caps.profileLevels[i].level);
					detail(profiles.toString());
				} catch (RuntimeException e) { detail("encoder_candidate unavailable=" + token(e.getClass().getSimpleName())); }
			}
		}
		void syncTrace() {
			try { syncTraceInternal(); } catch (RuntimeException | OutOfMemoryError ignored) { /* Diagnostics cannot fail the export. */ }
		}
		private void syncTraceInternal() {
			long capture = VideoDiagnostics.getExtendedCaptureId(); boolean enabled = false;
			synchronized (DebugTraceUtil.class) {
				if (closed || active != this) return;
				if (traceCapture != 0 && traceCapture != capture) releaseTraceLocked();
				if (capture != 0 && capture != blockedCapture && traceCapture == 0) {
					previousTracing = DebugTraceUtil.enableTracing;
					if (!previousTracing) DebugTraceUtil.reset();
					DebugTraceUtil.enableTracing = true; traceCapture = capture; enabled = true;
				}
			}
			if (enabled) CompressionDiagnostics.detail(capture, prefix() + "trace_enabled schema=1 media3=" + token(MediaLibraryInfo.VERSION)
					+ " attempt_elapsed_ms=" + (SystemClock.elapsedRealtime() - started) + " " + context);
		}
		private void releaseTraceLocked() {
			if (traceCapture == 0) return;
			DebugTraceUtil.enableTracing = previousTracing;
			if (!previousTracing) DebugTraceUtil.reset();
			traceCapture = 0;
		}
		void tick(int progressState, int progress, long bytes, long stallMs, boolean wakeLock) {
			syncTrace(); if (!VideoDiagnostics.isExtendedRecording()) return;
			detail("progress state=" + progressState + " percent=" + progress + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
					+ " no_progress_ms=" + stallMs + " output_bytes=" + bytes + " wake_lock=" + wakeLock);
			long now = SystemClock.elapsedRealtime();
			if (now - lastSnapshot >= 5000) { lastSnapshot = now; snapshot("periodic", false); }
		}
		void snapshot(String reason, boolean full) {
			try { snapshotInternal(reason, full); }
			catch (RuntimeException | OutOfMemoryError ignored) { /* Preserve conversion/cancellation under memory pressure. */ }
		}
		private void snapshotInternal(String reason, boolean full) {
			syncTrace(); long capture = VideoDiagnostics.getExtendedCaptureId(); if (capture == 0) return;
			String raw;
			synchronized (DebugTraceUtil.class) {
				if (traceCapture != capture || closed || active != this) return;
				raw = DebugTraceUtil.generateTraceSummary();
			}
			CompressionDiagnostics.detail(capture, prefix() + "trace_snapshot reason=" + token(reason) + " full=" + full);
			try {
				if (raw.length() > 2 * 1024 * 1024) { detail("trace_unavailable reason=oversized_summary"); return; }
				int end = raw.lastIndexOf('}');
				if (end < 0) { detail("trace_unavailable reason=no_event_summary"); return; }
				JSONObject trace = new JSONObject(raw.substring(0, end + 1));
				detail("trace_components=" + trace.length());
				Iterator<String> components = trace.keys(); int instance = 0;
				while (components.hasNext() && instance < 64) {
					String component = components.next(), stage = stage(component);
					JSONObject events = trace.optJSONObject(component); if (events == null) continue;
					Iterator<String> names = events.keys(); int count = 0; instance++;
					while (names.hasNext() && count++ < 48) {
						String event = names.next(); JSONObject data = events.optJSONObject(event); if (data == null) continue;
						CompressionDiagnostics.detail(capture, prefix() + "trace stage=" + stage + " instance=" + instance
								+ " event=" + event(event) + " count=" + data.optLong("count", -1)
								+ " first=" + times(data.optJSONArray("first"), full) + " last=" + times(data.optJSONArray("last"), full));
					}
				}
			} catch (Exception e) { detail("trace_unavailable reason=" + token(e.getClass().getSimpleName())); }
		}
		void result(String stage, ExportResult result, long bytes) {
			if (!VideoDiagnostics.isExtendedRecording()) return;
			detail("result stage=" + token(stage) + " output_bytes=" + bytes + " duration_ms=" + result.approximateDurationMs
					+ " frames=" + result.videoFrameCount + " width=" + result.width + " height=" + result.height
					+ " video_mime=" + mime(result.videoMimeType) + " audio_mime=" + mime(result.audioMimeType)
					+ " video_encoder=" + token(result.videoEncoderName) + " audio_encoder=" + token(result.audioEncoderName)
					+ " video_bitrate=" + result.averageVideoBitrate + " audio_bitrate=" + result.averageAudioBitrate
					+ " sample_rate=" + result.sampleRate + " channels=" + result.channelCount
					+ " video_process=" + result.videoConversionProcess + " audio_process=" + result.audioConversionProcess
					+ " optimization=" + result.optimizationResult);
		}
		Codec.EncoderFactory wrap(Codec.EncoderFactory delegate) {
			return new Codec.EncoderFactory() {
				@Override public Codec createForAudioEncoding(Format format, LogSessionId id) throws ExportException {
					if (VideoDiagnostics.isExtendedRecording()) detail("encoder_request video=false " + format(format));
					try { return created(delegate.createForAudioEncoding(format, id), false); }
					catch (ExportException e) { failure("audio_encoder_create", e); throw e; }
				}
				@Override public Codec createForVideoEncoding(Format format, LogSessionId id) throws ExportException {
					if (VideoDiagnostics.isExtendedRecording()) detail("encoder_request video=true " + format(format));
					try { return created(delegate.createForVideoEncoding(format, id), true); }
					catch (ExportException e) { failure("video_encoder_create", e); throw e; }
				}
				private Codec created(Codec codec, boolean video) {
					if (VideoDiagnostics.isExtendedRecording()) {
						try { detail("encoder_created video=" + video + " name=" + token(codec.getName()) + " " + format(codec.getConfigurationFormat())); }
						catch (RuntimeException e) { detail("encoder_description unavailable=" + token(e.getClass().getSimpleName())); }
					}
					return codec;
				}
				@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.ExperimentalApi.class)
				@Override public boolean isVideoFormatSupported(Format format) { return delegate.isVideoFormatSupported(format); }
				@Override public boolean audioNeedsEncoding() { return delegate.audioNeedsEncoding(); }
				@Override public boolean videoNeedsEncoding() { return delegate.videoNeedsEncoding(); }
			};
		}
		void close(String reason) {
			if (closed) return;
			try { snapshot(reason, true); }
			finally {
				synchronized (DebugTraceUtil.class) {
					closed = true;
					if (active == this) { releaseTraceLocked(); active = null; }
				}
			}
		}
	}
	static String stage(String component) {
		int bracket = component.indexOf('['); if (bracket >= 0) component = component.substring(0, bracket);
		return STAGES.contains(component) ? component : "Other";
	}
	static String event(String event) { return EVENTS.contains(event) ? event : "Other"; }
	static String times(JSONArray array, boolean full) {
		if (array == null) return "[]";
		StringBuilder text = new StringBuilder("["); int start = full ? 0 : Math.max(0, array.length() - 1);
		for (int i = start; i < Math.min(array.length(), start + 10); i++) {
			Matcher match = TIME.matcher(array.optString(i));
			if (i != start) text.append(','); text.append(match.find() ? match.group() : "unavailable");
		}
		return text.append(']').toString();
	}
}
