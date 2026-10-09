package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;

/** Leaves creation, callbacks, cancellation and dismissal entirely with the platform. */
public final class MotionDialogBuilder extends AlertDialog.Builder {
	public MotionDialogBuilder(Context context) { super(context); }
	public MotionDialogBuilder(Context context, int theme) { super(context, theme); }
	@Override public AlertDialog create() {
		AlertDialog dialog = super.create();
		SurfaceMotion.configureDialog(dialog);
		InterfaceAppearance.configureDialog(dialog);
		return dialog;
	}
}
