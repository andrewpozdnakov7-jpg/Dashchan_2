package com.mishiranu.dashchan.widget;

import android.view.View;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Explicit content ownership. Never walks native AlertController internals. */
public final class CompactDialogUi {
	private CompactDialogUi() {}

	public static boolean isEnabled() { return Preferences.isCompactDialogsEnabled(); }

	/** Mark the actual body separately from a scrolling wrapper. Marking changes no geometry. */
	public static void markForm(View content, View body) {
		if (content != null && body != null) content.setTag(R.id.compact_dialog_form_body, body);
	}

	/** Called once by the standard builder before native content is installed. */
	public static boolean prepareForm(View content) {
		if (!isEnabled() || content == null) return false;
		Object value = content.getTag(R.id.compact_dialog_form_body);
		if (!(value instanceof View)) return false;
		View body = (View) value;
		// Preserve each field, focus, listeners, preview, and the existing IME policy.
		float density = ResourceUtils.obtainDensity(body);
		body.setPaddingRelative(Math.round(24 * density), 0, Math.round(24 * density), Math.round(8 * density));
		return true;
	}
}
