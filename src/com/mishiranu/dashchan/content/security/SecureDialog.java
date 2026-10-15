package com.mishiranu.dashchan.content.security;

/** Platform Dialog with the same theme and behavior; each private window is registered before show. */
public class SecureDialog extends android.app.Dialog {
	public SecureDialog(android.content.Context context) { super(context); AppSecurity.protectDialog(this); }
	public SecureDialog(android.content.Context context, int theme) { super(context, theme); AppSecurity.protectDialog(this); }
}
