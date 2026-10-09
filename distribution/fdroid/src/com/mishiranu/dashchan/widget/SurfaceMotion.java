package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;

/** Stable distribution contract; experimental presentation is GitHub-only. */
public final class SurfaceMotion {
	private SurfaceMotion() {}
	public static void configureDialog(Dialog dialog) {}
	public static Context popupContext(Context context) { return context; }
	public static void registerPopupContext(Context context) {}
	public static void updatePopupPolicies() {}
	public static int popupStyle(int legacy) { return legacy; }
}
