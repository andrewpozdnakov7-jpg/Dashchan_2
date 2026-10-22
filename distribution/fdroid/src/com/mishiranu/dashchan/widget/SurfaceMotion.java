package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;

/** Stable distribution contract; experimental presentation is GitHub-only. */
public final class SurfaceMotion {
	private SurfaceMotion() {}
	public static void configureDialog(Dialog dialog) {
		com.mishiranu.dashchan.util.ScreenDiagnostics.observe(dialog.getWindow(), "dialog");
		com.mishiranu.dashchan.content.security.AppSecurity.protectDialog(dialog);
	}
	public static Context popupContext(Context context) { return com.mishiranu.dashchan.content.security.SecurePopupContext.wrap(context); }
	public static void registerPopupContext(Context context) {}
	public static void updatePopupPolicies() {}
	public static int popupStyle(int legacy) { return legacy; }
}
