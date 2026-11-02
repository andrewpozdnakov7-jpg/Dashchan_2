package com.mishiranu.dashchan.content.security;

/** Default presentation is unchanged. Explicit lock/media factories retain per-window protection. */
public final class SecureDialogBuilder extends android.app.AlertDialog.Builder {
	private boolean lockProfile, mediaProfile;
	public SecureDialogBuilder(android.content.Context context) { super(context); }
	public SecureDialogBuilder(android.content.Context context, int theme) { super(context, theme); }
	/** Only normal-process lock settings use this factory; never call from :security. */
	public static SecureDialogBuilder lock(android.content.Context context) {
		if (!com.mishiranu.dashchan.widget.InterfaceAppearance.isEnabled(context)) return new SecureDialogBuilder(context);
		SecureDialogBuilder builder = new SecureDialogBuilder(context, com.mishiranu.dashchan.R.style.Theme_Security_Presentation_Dialog);
		builder.lockProfile = true; return builder;
	}
	/** Explicit legacy editor profile; default captcha/editor constructors are unchanged. */
	public static SecureDialogBuilder media(android.content.Context context) {
		if (!com.mishiranu.dashchan.widget.InterfaceAppearance.isEnabled(context)) return new SecureDialogBuilder(context);
		SecureDialogBuilder builder = new SecureDialogBuilder(context, com.mishiranu.dashchan.R.style.Theme_Standard_Media_Dialog);
		builder.mediaProfile = true; return builder;
	}
	@Override public SecureDialogBuilder setTitle(int title) { return setTitle(getContext().getText(title)); }
	@Override public SecureDialogBuilder setTitle(CharSequence title) {
		if (lockProfile && title != null) setCustomTitle(SecurityPresentation.title(getContext(), title));
		else if (mediaProfile && title != null) {
			android.widget.TextView view = new android.widget.TextView(getContext()); view.setText(title);
			int pad = Math.round(24 * view.getResources().getDisplayMetrics().density);
			view.setPaddingRelative(pad, pad, pad, Math.round(16 * view.getResources().getDisplayMetrics().density));
			com.mishiranu.dashchan.widget.InterfaceAppearance.mediaHeading(view); setCustomTitle(view);
		} else super.setTitle(title);
		return this;
	}
	@Override public SecureDialogBuilder setView(android.view.View content) {
		if (mediaProfile) {
			android.widget.ScrollView scroll = new android.widget.ScrollView(getContext()) {
				@Override protected void onMeasure(int widthSpec, int heightSpec) {
					int height = Math.max(1, com.mishiranu.dashchan.util.ViewUtils.getWindowContentSize(getContext()).y / 2);
					if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) height = Math.min(height, MeasureSpec.getSize(heightSpec));
					super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
				}
			};
			scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2)); super.setView(scroll);
		} else super.setView(content);
		return this;
	}
	@Override public android.app.AlertDialog create() {
		android.app.AlertDialog dialog = super.create(); AppSecurity.protectDialog(dialog);
		if (lockProfile) SecurityPresentation.dialog(dialog);
		else if (mediaProfile) {
			com.mishiranu.dashchan.widget.InterfaceAppearance.configureMediaDialog(dialog);
		}
		return dialog;
	}
}
