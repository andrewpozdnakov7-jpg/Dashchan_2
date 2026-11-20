package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Window-owned IME avoidance for explicitly opted-in native floating forms. */
public final class DialogFormInsets {
	private DialogFormInsets() {}

	/** Call after MotionDialogBuilder.create() and before show(); preserves the IME state policy. */
	public static void configure(AlertDialog dialog) {
		Window window = dialog.getWindow();
		if (window == null) return;

		// Disable legacy resize/pan selection; typed window fitting owns IME avoidance below.
		int softInputMode = window.getAttributes().softInputMode;
		window.setSoftInputMode((softInputMode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
				| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

		// Keep the existing system-bar types, fit sides, floating bounds and appearance.
		// Do not subtract the IME again in content padding or a second window-position owner.
		WindowManager.LayoutParams attributes = window.getAttributes();
		attributes.setFitInsetsTypes(attributes.getFitInsetsTypes() | WindowInsets.Type.ime());
		attributes.setFitInsetsIgnoringVisibility(false);
		window.setAttributes(attributes);
		InterfaceAppearance.standardFormDialog(dialog);
	}
}
