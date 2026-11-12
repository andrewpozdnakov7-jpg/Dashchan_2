package com.mishiranu.dashchan.media.compression;

/** Format normalization is not a request to spend the entire upload-size allowance. */
public final class VideoConversionPolicy {
	private VideoConversionPolicy() {}
	public static String outputMime(String configuredCodec, boolean preferHevc, boolean manual, boolean hevcEncoderAvailable) {
		if (manual) return hevcEncoderAvailable ? "video/hevc" : "video/avc";
		return "avc".equals(configuredCodec) || "auto".equals(configuredCodec) && !preferHevc ? "video/avc" : "video/hevc";
	}
	public static boolean canCopyVideo(String input, String output, boolean hdr, boolean shrinking) {
		return canCopyVideo(input, output, hdr, shrinking, false);
	}
	public static boolean canCopyVideo(String input, String output, boolean hdr, boolean shrinking, boolean manual) {
		return input != null && input.equals(output) && (!hdr || manual) && !shrinking;
	}
	public static int bitrate(long sourceVideoBitrate, String input, String output, long budgetBitrate, boolean shrinking) {
		long preferred = budgetBitrate;
		if (!shrinking && sourceVideoBitrate > 0) {
			// AVC commonly needs more bits than HEVC for a visually close codec conversion.
			long multiplier = "video/hevc".equals(input) && "video/avc".equals(output) ? 2 : 1;
			preferred = sourceVideoBitrate > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : sourceVideoBitrate * multiplier;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.max(8_000, Math.min(preferred, budgetBitrate)));
	}
}
