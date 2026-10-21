package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;

/** Leaves creation, callbacks, cancellation and dismissal entirely with the platform. */
public final class MotionDialogBuilder extends AlertDialog.Builder {
	private boolean standard;
	private CharSequence standardTitle;
	private boolean customTitle;
	private boolean destructive;
	public MotionDialogBuilder(Context context) { super(context); }
	public MotionDialogBuilder(Context context, int theme) { super(context, theme); }
	/** Explicit opt-in: specialized posting/player/lock dialogs retain their existing constructor. */
	public static MotionDialogBuilder standard(Context context) {
		MotionDialogBuilder builder = new MotionDialogBuilder(InterfaceAppearance.standardDialogContext(context));
		builder.standard = true; return builder;
	}
	public static MotionDialogBuilder standard(Context context, boolean destructive) {
		MotionDialogBuilder builder = standard(context); builder.destructive = destructive; return builder;
	}
	@Override public MotionDialogBuilder setTitle(CharSequence title) {
		standardTitle = title; super.setTitle(title); return this;
	}
	@Override public MotionDialogBuilder setTitle(int title) { return setTitle(getContext().getText(title)); }
	@Override public MotionDialogBuilder setCustomTitle(android.view.View view) {
		customTitle = true; super.setCustomTitle(view); return this;
	}
	@Override public AlertDialog create() {
		if (standard && !customTitle && standardTitle != null && InterfaceAppearance.isEnabled(getContext())) {
			android.widget.TextView title = new android.widget.TextView(getContext());
			title.setId(com.mishiranu.dashchan.R.id.standard_dialog_title); title.setText(standardTitle);
			int padding = Math.round(24 * getContext().getResources().getDisplayMetrics().density);
			title.setPaddingRelative(padding, padding, padding, Math.round(16 * getContext().getResources().getDisplayMetrics().density));
			InterfaceAppearance.standardDialogTitle(title); super.setCustomTitle(title);
		}
		AlertDialog dialog = super.create();
		if (destructive && dialog.getWindow() != null) dialog.getWindow().getDecorView()
				.setTag(com.mishiranu.dashchan.R.id.standard_destructive_action, Boolean.TRUE);
		SurfaceMotion.configureDialog(dialog);
		if (standard) InterfaceAppearance.configureStandardDialog(dialog);
		else InterfaceAppearance.configureDialog(dialog);
		return dialog;
	}
}
