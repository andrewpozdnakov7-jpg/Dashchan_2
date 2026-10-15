package com.mishiranu.dashchan.content.security;

/** No presentation override. The returned AlertDialog owns its own security flag. */
public final class SecureDialogBuilder extends android.app.AlertDialog.Builder {
	public SecureDialogBuilder(android.content.Context context) { super(context); }
	public SecureDialogBuilder(android.content.Context context, int theme) { super(context, theme); }
	@Override public android.app.AlertDialog create() {
		android.app.AlertDialog dialog = super.create(); AppSecurity.protectDialog(dialog); return dialog;
	}
}
