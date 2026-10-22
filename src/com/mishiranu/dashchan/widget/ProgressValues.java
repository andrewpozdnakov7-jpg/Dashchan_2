package com.mishiranu.dashchan.widget;

/** Real operation values, independent of ProgressBar clamping or drawable state. */
public final class ProgressValues {
	public int value, max = 100;
	public boolean indeterminate;
	public boolean hasTotal() { return !indeterminate && max > 0; }
	public double fraction() { return hasTotal() ? Math.max(0d, Math.min(1d, (double) value / max)) : 0d; }
	public int level() { return (int) Math.round(fraction() * 10000); }
}
