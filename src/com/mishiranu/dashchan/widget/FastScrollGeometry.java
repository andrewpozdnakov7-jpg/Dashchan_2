package com.mishiranu.dashchan.widget;

/** Continuous adapter coordinates at both viewport edges, not an integer visible-child count. */
public final class FastScrollGeometry {
	public final int itemCount;
	public final double start;
	public final double end;
	public final double range;

	public FastScrollGeometry(int itemCount, double start, double end) {
		this.itemCount = Math.max(0, itemCount);
		this.start = clamp(start, 0, this.itemCount);
		this.end = clamp(end, this.start, this.itemCount);
		// Distance before the viewport + distance after it. At the bottom the latter tends
		// continuously to zero; there is no separate "cannot scroll -> 100%" drawing rule.
		range = this.start + this.itemCount - this.end;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Double.isNaN(value) ? minimum : Math.max(minimum, Math.min(value, maximum));
	}

	public float offset() { return range > 0 ? (float) clamp(start / range, 0, 1) : 0f; }
	public double viewportFraction() { return itemCount > 0 ? (end - start) / itemCount : 0; }

	/** The inverse of offset() for this viewport, including the hidden fraction of an item. */
	public double targetCoordinate(float offset) {
		// A last item taller than the viewport can itself be partially scrolled offscreen.
		// Keep its fractional coordinate, but never produce an out-of-bounds item index.
		return clamp(clamp(offset, 0, 1) * range, 0, itemCount > 0 ? Math.nextDown((double) itemCount) : 0);
	}

	/** Project a mark from its own immutable item snapshot onto the same navigation scale. */
	public static float markOffset(int position, int itemCount, double viewportFraction) {
		double range = itemCount * (1 - clamp(viewportFraction, 0, 1));
		return range > 0 ? (float) clamp(position / range, 0, 1) : 0f;
	}
}
