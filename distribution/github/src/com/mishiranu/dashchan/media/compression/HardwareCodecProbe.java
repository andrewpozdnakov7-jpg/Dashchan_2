package com.mishiranu.dashchan.media.compression;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Tiny synthetic clip in memory. No user media, network, files or claims about arbitrary HDR/4K. */
@SuppressWarnings("deprecation") // Probe optional byte-buffer YUV modes as well as modern flexible YUV.
public final class HardwareCodecProbe {
	public static final class Report {
		public final String mime;
		public boolean encoderDeclared, decoderDeclared, encoded, decoded;
		public boolean encodingTested, decodingTested;
		Report(String mime) { this.mime = mime; }
	}
	private static final class Sample {
		final byte[] bytes; final long pts; final int flags;
		Sample(byte[] bytes, long pts, int flags) { this.bytes = bytes; this.pts = pts; this.flags = flags; }
	}
	private HardwareCodecProbe() {}
	public static Report run(String mime) {
		Report report = new Report(mime);
		List<MediaCodecInfo> encoders = HardwareVideoCodecs.find(mime, true), decoders = HardwareVideoCodecs.find(mime, false);
		report.encoderDeclared = !encoders.isEmpty(); report.decoderDeclared = !decoders.isEmpty();
		List<Sample> samples = new ArrayList<>(); MediaFormat format = null;
		for (MediaCodecInfo info : encoders) {
			if (Thread.currentThread().isInterrupted()) return report;
			MediaCodec codec = null;
			try {
				MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
				if (!caps.getVideoCapabilities().isSizeSupported(320, 240)) continue;
				int color = -1;
				for (int c : caps.colorFormats) if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
						|| c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
						|| c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { color = c; break; }
				if (color == -1) continue;
				report.encodingTested = true; samples.clear(); format = null;
				MediaFormat requested = MediaFormat.createVideoFormat(mime, 320, 240);
				requested.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
				requested.setInteger(MediaFormat.KEY_BIT_RATE, 200_000);
				requested.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
				requested.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
				codec = MediaCodec.createByCodecName(info.getName()); codec.configure(requested, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
				MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo(); int frame = 0, framesOut = 0;
				long deadline = SystemClock.elapsedRealtime() + 8000;
				while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted()) {
					if (frame <= 4) {
						int input = codec.dequeueInputBuffer(10_000);
						if (input >= 0) {
							ByteBuffer buffer = codec.getInputBuffer(input); buffer.clear();
							int length = frame == 4 ? 0 : 320 * 240 * 3 / 2;
							if (buffer.remaining() < length) throw new IllegalStateException();
							for (int i = 0; i < length; i++) buffer.put((byte) (i < 320 * 240 ? 32 + frame * 32 : 128));
							codec.queueInputBuffer(input, 0, length, frame * 100_000L, frame == 4 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); frame++;
						}
					}
					int out = codec.dequeueOutputBuffer(bufferInfo, 10_000);
					if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.getOutputFormat();
					else if (out >= 0) {
						if (bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
							ByteBuffer b = codec.getOutputBuffer(out); b.position(bufferInfo.offset); b.limit(bufferInfo.offset + bufferInfo.size);
							byte[] bytes = new byte[b.remaining()]; b.get(bytes); samples.add(new Sample(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags)); framesOut++;
						}
						codec.releaseOutputBuffer(out, false);
						if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { report.encoded = framesOut > 0 && format != null; break; }
					}
				}
			} catch (Exception ignored) { /* A failed probe is distinct from missing advertised support. */ }
			finally { release(codec); }
			if (report.encoded) break;
		}
		if (!report.encoded || format == null) return report;
		for (MediaCodecInfo info : decoders) {
			if (Thread.currentThread().isInterrupted()) return report;
			MediaCodec codec = null;
			try {
				report.decodingTested = true; codec = MediaCodec.createByCodecName(info.getName());
				codec.configure(format, null, null, 0); codec.start(); int next = 0, framesOut = 0;
				MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo(); long deadline = SystemClock.elapsedRealtime() + 8000;
				while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted()) {
					if (next <= samples.size()) {
						int in = codec.dequeueInputBuffer(10_000);
						if (in >= 0) {
							if (next == samples.size()) codec.queueInputBuffer(in, 0, 0, 500_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							else { Sample s = samples.get(next); ByteBuffer b = codec.getInputBuffer(in); b.clear(); b.put(s.bytes);
								codec.queueInputBuffer(in, 0, s.bytes.length, s.pts, s.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM); }
							next++;
						}
					}
					int out = codec.dequeueOutputBuffer(bufferInfo, 10_000);
					if (out >= 0) { if (bufferInfo.size > 0) framesOut++; codec.releaseOutputBuffer(out, false);
						if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { report.decoded = framesOut > 0; break; } }
				}
			} catch (Exception ignored) {}
			finally { release(codec); }
			if (report.decoded) break;
		}
		return report;
	}
	private static void release(MediaCodec codec) {
		if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) {} try { codec.release(); } catch (RuntimeException ignored) {} }
	}
}
