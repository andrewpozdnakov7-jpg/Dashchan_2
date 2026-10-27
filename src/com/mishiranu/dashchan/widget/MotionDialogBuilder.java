package com.mishiranu.dashchan.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ViewUtils;

/** Leaves creation, callbacks, cancellation and dismissal entirely with the platform. */
public final class MotionDialogBuilder extends AlertDialog.Builder {
	private boolean standard;
	private boolean page;
	private CharSequence standardTitle;
	private boolean customTitle;
	private boolean destructive;
	private View inputView;
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
	/** Native full-page WebView shell: standard actions, but no modal width/radius cap. */
	public static MotionDialogBuilder standardPage(Context context) {
		MotionDialogBuilder builder = standard(context); builder.page = true; return builder;
	}
	/** Single owner for the outside inset of a simple standard input. Legacy views stay untouched. */
	public MotionDialogBuilder setInputView(View view) {
		setView(view); inputView = view;
		return this;
	}
	@Override public MotionDialogBuilder setView(View view) {
		inputView = null; super.setView(view); return this;
	}
	@Override public MotionDialogBuilder setView(int layoutResId) {
		inputView = null; super.setView(layoutResId); return this;
	}
	/**
	 * Native AlertController chooses message OR list. Keep both without list headers that
	 * shift callback positions. Only standard ON uses the bounded, scrollable message title.
	 */
	public MotionDialogBuilder setItemsWithMessage(CharSequence message, CharSequence[] items,
			DialogInterface.OnClickListener listener) {
		super.setItems(items, listener);
		if (!standard || !InterfaceAppearance.isEnabled(getContext())) {
			super.setMessage(message);
			return this;
		}
		Context context = getContext();
		int inset = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_text);
		int gap = Math.round(16 * context.getResources().getDisplayMetrics().density);
		LinearLayout header = new LinearLayout(context);
		header.setOrientation(LinearLayout.VERTICAL);
		if (standardTitle != null) {
			TextView title = new TextView(context);
			title.setId(R.id.standard_dialog_title); title.setText(standardTitle);
			title.setPaddingRelative(inset, inset, inset, gap);
			InterfaceAppearance.standardDialogTitle(title);
			header.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		}
		ScrollView scroll = new ScrollView(context) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				int limit = Math.max(1, ViewUtils.getWindowContentSize(getContext()).y / 3);
				if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
					limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
				}
				super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
			}
		};
		TextView body = new TextView(context);
		body.setId(android.R.id.message); body.setText(message);
		body.setPaddingRelative(inset, standardTitle == null ? inset : 0, inset, gap);
		scroll.addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		header.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		super.setMessage(null);
		setCustomTitle(header);
		return this;
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
		if (inputView != null && standard && InterfaceAppearance.isEnabled(getContext())) {
			int inset = getContext().getResources().getDimensionPixelSize(R.dimen.dialog_padding_text);
			// This spacing overload is public on AlertDialog, not on its Builder.
			// Configure before show; do not eagerly create the dialog's content.
			dialog.setView(inputView, inset, 0, inset, 0);
		}
		if (destructive && dialog.getWindow() != null) dialog.getWindow().getDecorView()
				.setTag(com.mishiranu.dashchan.R.id.standard_destructive_action, Boolean.TRUE);
		SurfaceMotion.configureDialog(dialog);
		if (page) InterfaceAppearance.configureStandardPageDialog(dialog);
		else if (standard) InterfaceAppearance.configureStandardDialog(dialog);
		else InterfaceAppearance.configureDialog(dialog);
		if (com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics.isRecording()) {
			com.mishiranu.dashchan.util.ScreenDiagnostics.note(dialog.getWindow(), "builder_created",
					"standardRequested=" + standard + " destructive=" + destructive
							+ " input=" + (inputView != null) + " caller="
							+ com.mishiranu.dashchan.util.ScreenDiagnostics.appCaller());
		}
		return dialog;
	}
}
