package com.mishiranu.dashchan.ui.preference;

/** One width plan for the complete table, including its header. Inputs/outputs are pixels. */
final class StatisticsColumnWidths {
	private StatisticsColumnWidths() {}
	static int[] resolve(int[] minimum, int available) {
		int[] result = minimum.clone();
		int total = 0;
		for (int width : result) total += width;
		int spare = Math.max(0, available - total);
		int[] weights = {3, 2, 2, 2};
		int allocated = 0;
		for (int i = 0; i < result.length; i++) {
			int extra = spare * weights[i] / 9;
			result[i] += extra; allocated += extra;
		}
		result[0] += spare - allocated;
		return result;
	}
}
