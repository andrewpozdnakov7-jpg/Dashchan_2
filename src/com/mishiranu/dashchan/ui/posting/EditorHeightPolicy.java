package com.mishiranu.dashchan.ui.posting;

/** Shared, content-independent editor bounds for the real form and its offline preview. */
public final class EditorHeightPolicy {
	private EditorHeightPolicy() {}

	public enum Mode { AUTOMATIC, COMPACT, EXPANDED, FIXED }
	public static final int DEFAULT_LINES = 8;
	public static final int MIN_LINES = 2;
	public static final int MAX_LINES = 20;

	public static int clampLines(int lines) {
		return Math.max(MIN_LINES, Math.min(MAX_LINES, lines));
	}

	/** With the keyboard hidden attachments may consume at most one fifth of the
	 * empty-form height. With the keyboard visible all attachments belong below
	 * the editor in the outer form's scroll range and do not reduce its height. */
	public static int adaptiveAutomaticHeight(int available, int otherHeight, int attachmentHeight,
			int minimum, boolean keyboard) {
		minimum = Math.max(1, minimum);
		otherHeight = Math.max(0, otherHeight);
		attachmentHeight = Math.max(0, Math.min(otherHeight, attachmentHeight));
		int baseline = Math.max(minimum, Math.max(0, available) - (otherHeight - attachmentHeight));
		return keyboard ? baseline : Math.max(minimum, baseline - Math.min(attachmentHeight, baseline / 5));
	}

	public static final class Bounds {
		public final boolean pixels;
		public final int minimum, maximum;
		private Bounds(boolean pixels, int minimum, int maximum) {
			this.pixels = pixels;
			this.minimum = minimum;
			this.maximum = maximum;
		}
	}

	public static Bounds calculate(Mode mode, boolean keyboard, int available, int otherHeight,
			int padding, int lineHeight, int fixedLines) {
		available = Math.max(1, available);
		padding = Math.max(0, padding);
		lineHeight = Math.max(1, lineHeight);
		if (mode == Mode.AUTOMATIC && !keyboard) {
			int height = Math.min(available, Math.max(padding + 4 * lineHeight, available - otherHeight));
			return new Bounds(true, height, height);
		}
		int capacity = Math.max(1, (available - padding) / lineHeight);
		int minimum = mode == Mode.EXPANDED ? 8 : 4;
		int maximum = mode == Mode.EXPANDED ? 16 : 9;
		if (mode == Mode.FIXED) minimum = maximum = clampLines(fixedLines);
		maximum = Math.min(maximum, capacity);
		return new Bounds(false, Math.min(minimum, maximum), maximum);
	}
}
