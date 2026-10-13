package com.mishiranu.dashchan.media;

/** Coarse, non-spatial statistics only: no pixel values, hashes or images enter the log. */
final class VideoFrameStatistics {
	private VideoFrameStatistics() {}

	static String describe(int[] pixels) {
		if (pixels.length == 0) return "samples=0";
		long luma = 0L;
		int opaque = 0;
		int black = 0;
		for (int color : pixels) {
			if ((color >>> 24) < 250) continue;
			opaque++;
			int r = color >>> 16 & 255;
			int g = color >>> 8 & 255;
			int b = color & 255;
			luma += (54 * r + 183 * g + 19 * b) >> 8;
			if (Math.max(r, Math.max(g, b)) <= 12) black++;
		}
		return "samples=" + pixels.length + " opaque_permille=" + (1000L * opaque / pixels.length)
				+ " mean_luma=" + (opaque > 0 ? luma / opaque : -1L)
				+ " near_black_permille=" + (opaque > 0 ? 1000L * black / opaque : -1L);
	}
}
