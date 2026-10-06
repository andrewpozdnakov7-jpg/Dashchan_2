package com.mishiranu.dashchan.ui.gallery;

/** Read-only detector for stale pre-rotation PiP coordinates; never repairs window placement. */
final class PipBoundsMonitor {
	private static final long ROTATION_WINDOW_MS = 3000L;
	private int rotation = -1;
	private long rotatedAt = -1L;
	private boolean reported;

	boolean observeRotation(int rotation, long now) {
		if (this.rotation != rotation) {
			// A half-turn does not swap the coordinate space. Entry is only a baseline.
			rotatedAt = this.rotation >= 0 && (this.rotation & 1) != (rotation & 1) ? now : -1L;
			this.rotation = rotation;
			reported = false;
			return true;
		}
		return false;
	}

	boolean isWatching(long now) {
		return !reported && rotatedAt >= 0L && now >= rotatedAt && now - rotatedAt <= ROTATION_WINDOW_MS;
	}

	boolean shouldReport(long now, int left, int top, int right, int bottom,
			int displayLeft, int displayTop, int displayRight, int displayBottom) {
		if (!isWatching(now)) return false;
		long width = (long) right - left;
		long height = (long) bottom - top;
		long displayWidth = (long) displayRight - displayLeft;
		long displayHeight = (long) displayBottom - displayTop;
		if (width <= 0L || height <= 0L || displayWidth <= 0L || displayHeight <= 0L
				|| displayWidth == displayHeight) return false;
		// A deliberately stashed PiP still exposes an edge: do not report it as lost.
		if (left < displayRight && right > displayLeft && top < displayBottom && bottom > displayTop) {
			return false;
		}
		// Match the actual failure: bounds are legal in the previous, swapped display,
		// but far beyond the current one. Ignore a drag-to-dismiss at its edge.
		if (left < displayLeft || top < displayTop
				|| (long) right - displayLeft > displayHeight
				|| (long) bottom - displayTop > displayWidth) return false;
		long gap = Math.min(width, height) / 2L;
		return (long) left - displayRight >= gap || (long) top - displayBottom >= gap;
	}

	void markReported() {
		reported = true;
	}

	void reset() {
		rotation = -1;
		rotatedAt = -1L;
		reported = false;
	}
}
