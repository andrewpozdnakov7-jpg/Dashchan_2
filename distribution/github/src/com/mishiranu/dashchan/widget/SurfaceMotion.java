package com.mishiranu.dashchan.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.WeakHashMap;

/** Stage 5: only presentation styles; no callback, focus or dismissal interception. */
public final class SurfaceMotion {
	private SurfaceMotion() {}
	public static void configureDialog(Dialog dialog) {
		configureDialog(dialog, InterfaceMotion.isEnabled(), InterfaceMotion.duration(1) > 0);
	}
	static void configureDialog(Dialog dialog, boolean enabled, boolean animate) {
		if (enabled && dialog.getWindow() != null) dialog.getWindow().setWindowAnimations(animate ? R.style.Animation_Motion_Dialog : 0);
	}
	// Popup presenters cache their Context. Update that context's theme through public APIs,
	// instead of recreating the Activity or reaching into Toolbar's private presenter fields.
	private static final WeakHashMap<Resources.Theme, Resources.Theme> POPUPS = new WeakHashMap<>();
	public static void registerPopupContext(Context context) {
		Resources.Theme theme = context.getTheme();
		if (!POPUPS.containsKey(theme)) {
			Resources.Theme original = context.getResources().newTheme(); original.setTo(theme);
			POPUPS.put(theme, original);
		}
		applyPopupPolicy(theme, POPUPS.get(theme), InterfaceMotion.isEnabled(), InterfaceMotion.duration(1) > 0);
	}
	static void applyPopupPolicy(Resources.Theme theme, Resources.Theme original, boolean enabled, boolean animate) {
		theme.setTo(original);
		if (enabled) theme.applyStyle(animate ? R.style.Theme_Motion_Popup : R.style.Theme_Motion_Popup_Static, true);
		else {
			android.util.TypedValue value = new android.util.TypedValue();
			if (original.resolveAttribute(android.R.attr.popupMenuStyle, value, true)
					&& (value.resourceId == R.style.Widget_Motion_Popup || value.resourceId == R.style.Widget_Motion_Popup_Static)) {
				// Copies of an already-styled popup inherit our generic widget overrides.
				// Restore their native defaults without replacing the surrounding palette.
				theme.applyStyle(R.style.Theme_Motion_Popup_Legacy, true);
			}
		}
	}
	public static void updatePopupPolicies() {
		for (java.util.Map.Entry<Resources.Theme, Resources.Theme> entry : POPUPS.entrySet()) {
			applyPopupPolicy(entry.getKey(), entry.getValue(), InterfaceMotion.isEnabled(), InterfaceMotion.duration(1) > 0);
		}
	}
	public static Context popupContext(Context context) {
		if (!InterfaceMotion.isEnabled()) return context;
		Resources.Theme theme = context.getResources().newTheme(); theme.setTo(context.getTheme());
		theme.applyStyle(R.style.Theme_Motion_Popup_Marker, true);
		Context result = new ContextThemeWrapper(context, theme);
		registerPopupContext(result); return result;
	}
	public static int popupStyle(int legacy) {
		return InterfaceMotion.isEnabled() ? InterfaceMotion.duration(1) > 0
				? R.style.Widget_Motion_Popup_Overlap : R.style.Widget_Motion_Popup_Static_Overlap : legacy;
	}
}
