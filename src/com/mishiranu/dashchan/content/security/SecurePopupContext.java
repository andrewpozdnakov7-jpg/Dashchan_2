package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/** Public WindowManager delegation: popup subwindows do not inherit Activity FLAG_SECURE. */
public final class SecurePopupContext extends ContextWrapper {
	private WindowManager manager;
	private SecurePopupContext(Context context) { super(context); }
	public static Context wrap(Context context) {
		return context instanceof SecurePopupContext ? context : new SecurePopupContext(context);
	}
	@Override public Object getSystemService(String name) {
		if (!WINDOW_SERVICE.equals(name)) return super.getSystemService(name);
		if (manager == null) manager = protect(this, (WindowManager) super.getSystemService(name));
		return manager;
	}
	static WindowManager protect(Context context, WindowManager delegate) {
		return new WindowManager() {
			@Override public void addView(View view, ViewGroup.LayoutParams params) {
				boolean originallySecure = params instanceof LayoutParams && (((LayoutParams) params).flags & LayoutParams.FLAG_SECURE) != 0;
				boolean cover = false;
				if (params instanceof LayoutParams && AppSecurity.isProtected(context)) {
					((LayoutParams) params).flags |= LayoutParams.FLAG_SECURE;
					cover = SecurityStore.locked(context);
				}
				delegate.addView(view, params);
				AppSecurity.registerPopup(view, delegate, originallySecure, cover);
			}
			@Override public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
				if (params instanceof LayoutParams && AppSecurity.isProtected(context)) ((LayoutParams) params).flags |= LayoutParams.FLAG_SECURE;
				delegate.updateViewLayout(view, params);
			}
			@Override public void removeView(View view) { AppSecurity.unregisterPopup(view); delegate.removeView(view); }
			@Override public void removeViewImmediate(View view) { AppSecurity.unregisterPopup(view); delegate.removeViewImmediate(view); }
			@Deprecated @Override public android.view.Display getDefaultDisplay() { return delegate.getDefaultDisplay(); }
			@Override public android.view.WindowMetrics getCurrentWindowMetrics() { return delegate.getCurrentWindowMetrics(); }
			@Override public android.view.WindowMetrics getMaximumWindowMetrics() { return delegate.getMaximumWindowMetrics(); }
		};
	}
}
