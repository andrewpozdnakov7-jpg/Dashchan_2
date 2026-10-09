package com.mishiranu.dashchan.widget;

import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

/** Flavor boundary: F-Droid retains the existing DialogStack presentation. */
public final class DialogStackMotion {
	public DialogStackMotion(View content, View root) {}
	public void configure(Window window, float dim, int legacyAnimations) {}
	public Snapshot capture(View card) { return null; }
	public boolean enter(Snapshot previous, View previousLive, View incoming, boolean first, boolean restoring) { return false; }
	public void leave(Snapshot outgoing, View incoming, boolean last, Runnable close) { if (close != null) close.run(); }
	public void finish() {}
	public void closed(Window window) {}
	public static void discard(Snapshot snapshot) {}
	public static boolean blocks(View host, MotionEvent event) { return false; }
	public static void lostFocus(View host) {}
	public static final class Snapshot {}
}
