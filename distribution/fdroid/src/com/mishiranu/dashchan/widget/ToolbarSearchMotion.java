package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.ViewGroup;

/** Stable distribution contract; no experimental snapshots or rendering. */
public final class ToolbarSearchMotion {
	public static ToolbarSearchMotion prepare(ViewGroup toolbar, boolean expanding) { return null; }
	public static void finish(View toolbar) {}
	public void commit() {}
	public void run() {}
}
