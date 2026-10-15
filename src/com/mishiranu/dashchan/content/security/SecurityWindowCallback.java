package com.mishiranu.dashchan.content.security;

import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;

/** Public Window callback only: no private Android APIs and no replacement dialog listeners. */
final class SecurityWindowCallback implements Window.Callback {
	private final Window window;
	private final Window.Callback delegate;
	private boolean protectedWindow;
	SecurityWindowCallback(Window window, Window.Callback delegate, boolean protect) { this.window = window; this.delegate = delegate; protectedWindow = protect; }
	void setProtected(boolean value) { protectedWindow = value; }
	private boolean blocked() {
		if (!protectedWindow || SecuritySessionProvider.isUnlocked(window.getContext())) return false;
		AppSecurity.dialogWindowAttached(window);
		return true;
	}
	private void protect() { if (protectedWindow) AppSecurity.dialogWindowAttached(window); }
	@Override public boolean dispatchTouchEvent(MotionEvent e) { return blocked() || delegate.dispatchTouchEvent(e); }
	@Override public boolean dispatchKeyEvent(KeyEvent e) { return blocked() || delegate.dispatchKeyEvent(e); }
	@Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return blocked() || delegate.dispatchKeyShortcutEvent(e); }
	@Override public boolean dispatchTrackballEvent(MotionEvent e) { return blocked() || delegate.dispatchTrackballEvent(e); }
	@Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return blocked() || delegate.dispatchGenericMotionEvent(e); }
	@Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return !blocked() && delegate.dispatchPopulateAccessibilityEvent(e); }
	@Override public void onAttachedToWindow() { delegate.onAttachedToWindow(); protect(); }
	@Override public void onContentChanged() { delegate.onContentChanged(); protect(); }
	@Override public void onWindowFocusChanged(boolean focus) {
		if (!focus || !blocked()) delegate.onWindowFocusChanged(focus);
		if (focus) protect();
	}
	@Override public void onDetachedFromWindow() { delegate.onDetachedFromWindow(); }
	@Override public void onWindowAttributesChanged(WindowManager.LayoutParams params) { delegate.onWindowAttributesChanged(params); }
	@Override public View onCreatePanelView(int feature) { return blocked() ? null : delegate.onCreatePanelView(feature); }
	@Override public boolean onCreatePanelMenu(int feature, Menu menu) { return !blocked() && delegate.onCreatePanelMenu(feature, menu); }
	@Override public boolean onPreparePanel(int feature, View view, Menu menu) { return !blocked() && delegate.onPreparePanel(feature, view, menu); }
	@Override public boolean onMenuOpened(int feature, Menu menu) { return !blocked() && delegate.onMenuOpened(feature, menu); }
	@Override public boolean onMenuItemSelected(int feature, MenuItem item) { return blocked() || delegate.onMenuItemSelected(feature, item); }
	@Override public void onPanelClosed(int feature, Menu menu) { delegate.onPanelClosed(feature, menu); }
	@Override public boolean onSearchRequested() { return !blocked() && delegate.onSearchRequested(); }
	@Override public boolean onSearchRequested(SearchEvent event) { return !blocked() && delegate.onSearchRequested(event); }
	@Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return blocked() ? null : delegate.onWindowStartingActionMode(callback); }
	@Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return blocked() ? null : delegate.onWindowStartingActionMode(callback, type); }
	@Override public void onActionModeStarted(ActionMode mode) { delegate.onActionModeStarted(mode); }
	@Override public void onActionModeFinished(ActionMode mode) { delegate.onActionModeFinished(mode); }
	@Override public void onPointerCaptureChanged(boolean captured) { delegate.onPointerCaptureChanged(captured); }
	@Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> groups, Menu menu, int device) {
		if (!blocked()) delegate.onProvideKeyboardShortcuts(groups, menu, device);
	}
}
