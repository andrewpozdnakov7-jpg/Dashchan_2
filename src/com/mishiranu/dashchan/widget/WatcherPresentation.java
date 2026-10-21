package com.mishiranu.dashchan.widget;

/** Pure view state: never mutates counters or infers an unsupported UNKNOWN state. */
public final class WatcherPresentation {
	public enum Mode {DISABLED, ACTIVE, UNREAD, CHECKING, UNAVAILABLE, ERROR, DELETED}
	public final boolean enabled;
	public final int count;
	public final Mode mode;
	public final String text;

	private WatcherPresentation(boolean enabled, int count, Mode mode) {
		this.enabled = enabled; this.count = count; this.mode = mode;
		text = mode == Mode.DELETED && count <= 0 ? "0" : format(count);
	}

	public static WatcherPresentation of(boolean enabled, boolean unavailable, boolean running,
			int count, boolean deleted, boolean error) {
		if (!enabled) { count = 0; error = false; running = false; }
		Mode mode = deleted ? Mode.DELETED : !enabled ? Mode.DISABLED : error ? Mode.ERROR
				: unavailable ? Mode.UNAVAILABLE : running ? Mode.CHECKING : count > 0 ? Mode.UNREAD : Mode.ACTIVE;
		return new WatcherPresentation(enabled, count, mode);
	}

	public static String format(int value) {
		long magnitude = Math.abs((long) value);
		String sign = value < 0 ? "−" : value > 0 ? "+" : "";
		if (magnitude < 1000) return sign + magnitude;
		long divisor = magnitude >= 1_000_000_000 ? 1_000_000_000 : magnitude >= 1_000_000 ? 1_000_000 : 1000;
		String suffix = divisor == 1_000_000_000 ? "B" : divisor == 1_000_000 ? "M" : "K";
		return (value < 0 ? "−" : "") + magnitude / divisor + suffix + (value > 0 ? "+" : "");
	}
}
