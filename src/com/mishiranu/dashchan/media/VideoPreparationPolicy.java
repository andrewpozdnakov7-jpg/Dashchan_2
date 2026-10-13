package com.mishiranu.dashchan.media;

/** Bound speculative work only; normal foreground playback remains unrestricted. */
public final class VideoPreparationPolicy {
	private VideoPreparationPolicy() {}
	public static boolean fileAllowed(long length, boolean lowRam) {
		return !lowRam && length > 0L && length <= 64L * 1024L * 1024L;
	}
	public static boolean dimensionsAllowed(int width, int height) {
		return width > 0 && height > 0 && (long) width * height <= 1920L * 1080L;
	}
}
