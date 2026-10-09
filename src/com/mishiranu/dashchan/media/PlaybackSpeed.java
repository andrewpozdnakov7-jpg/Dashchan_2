package com.mishiranu.dashchan.media;

import java.util.ArrayList;
import java.util.Locale;

/** Pure playback-speed rules. Stored/player values are thousandths: 1000 means 1x. */
public final class PlaybackSpeed {
	public static final int NORMAL = 1000;
	public static final int MINIMUM = 10;
	public static final int MAXIMUM = 10000;
	public static final int ONE_PERCENT = 10;

	private PlaybackSpeed() {}

	public static int clamp(int speed) {
		return Math.max(MINIMUM, Math.min(speed, MAXIMUM));
	}

	/** Custom input uses the existing one-percent rounding, then clamps to the player range. */
	public static int normalizeCustom(int speed) {
		return clamp(Math.round(speed / (float) ONE_PERCENT) * ONE_PERCENT);
	}

	/** An unsupported remembered speed falls back to 1x, even if 1x is absent from the preset list. */
	public static int normalizePreset(int speed, int[] presets) {
		for (int preset : presets) {
			if (preset == speed) {
				return speed;
			}
		}
		return NORMAL;
	}

	public static String format(int speed) {
		if (speed % NORMAL == 0) {
			return String.format(Locale.US, "%dx", speed / NORMAL);
		} else if (speed % 100 == 0) {
			return String.format(Locale.US, "%.1fx", speed / (float) NORMAL);
		}
		return String.format(Locale.US, "%.2fx", speed / (float) NORMAL);
	}

	/** Read legacy stored values without rounding: keep order, skip invalid values and duplicates. */
	public static int[] parsePresets(String value, int[] fallback) {
		ArrayList<Integer> presets = new ArrayList<>();
		for (String item : value.split(",")) {
			try {
				int speed = Integer.parseInt(item.trim());
				if (speed >= MINIMUM && speed <= MAXIMUM && !presets.contains(speed)) {
					presets.add(speed);
				}
			} catch (NumberFormatException e) {
				// Ignore invalid stored values and fall back when none remain.
			}
		}
		if (presets.isEmpty()) {
			return fallback.clone();
		}
		int[] result = new int[presets.size()];
		for (int i = 0; i < presets.size(); i++) {
			result[i] = presets.get(i);
		}
		return result;
	}

	/** Write policy is deliberately different: round valid inputs, retaining duplicates and order. */
	public static String encodePresets(int[] presets, String fallback) {
		StringBuilder builder = new StringBuilder();
		for (int speed : presets) {
			speed = Math.round(speed / (float) ONE_PERCENT) * ONE_PERCENT;
			if (speed < MINIMUM || speed > MAXIMUM) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(speed);
		}
		return builder.length() > 0 ? builder.toString() : fallback;
	}
}
