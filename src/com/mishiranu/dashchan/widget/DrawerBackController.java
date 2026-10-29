package com.mishiranu.dashchan.widget;

import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;
import androidx.drawerlayout.widget.DrawerLayout;
import com.mishiranu.dashchan.util.InterfaceMotion;

/** Shared by both distributions: owns Back only while the unlocked drawer is visible. */
public final class DrawerBackController implements DrawerLayout.DrawerListener {
	private final DrawerLayout layout;
	private final View panel;
	private final PredictiveBackMotion motion;
	private boolean resumed = true;
	private boolean gestureStarted, imeRoute;
	private boolean invocationHandled;
	private Object registration;
	private final Runnable register = this::register;

	public DrawerBackController(DrawerLayout layout, View panel, Runnable invalidate) {
		this.layout = layout; this.panel = panel;
		motion = new PredictiveBackMotion(invalidate);
		layout.addDrawerListener(this);
		layout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) { schedule(); }
			@Override public void onViewDetachedFromWindow(View view) { unregister(); motion.finish(); }
		});
		panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) motion.finish();
		});
	}

	public boolean isTracking() { return gestureStarted || motion.isTracking(); }
	public boolean isBusy() { return motion.isBusy(); }
	public boolean hasConsumedInvocation() { return invocationHandled; }
	public float progress() { return Math.min(1f, motion.getProgress()); }
	public void resume() { resumed = true; invocationHandled = false; schedule(); }
	public void suspend() { resumed = false; unregister(); reset(); }
	public void update() { reset(); unregister(); schedule(); }
	public void reset() { gestureStarted = imeRoute = false; motion.finish(); }
	public void recover(boolean animate) { gestureStarted = imeRoute = false; if (animate) motion.cancel(); else motion.finish(); }
	public void progress(float value) { motion.progress(value); }

	public boolean start() {
		if (motion.isCommitting()) return false;
		motion.finish();
		gestureStarted = imeRoute = false;
		if (!enabled() || !layout.isDrawerVisible(panel) || !unlocked()) return false;
		gestureStarted = true;
		imeRoute = PredictiveBackMotion.isImeVisible(layout);
		if (imeRoute) return true;
		if (!layout.isDrawerOpen(panel) || !InterfaceMotion.isPredictiveBackEnabled()) return true;
		boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		return motion.start(panel, rtl ? 1f : -1f, panel.getWidth() * BackMotionSpec.DIALOG_TRAVEL, BackMotionSpec.DRAWER_SHRINK);
	}

	public void commit() {
		if (motion.isCommitting()) return;
		if (motion.isBusy() && !motion.isTracking()) motion.finish();
		if (invocationHandled) return;
		invocationHandled = true; layout.postOnAnimation(() -> invocationHandled = false);
		boolean hideIme = gestureStarted ? imeRoute : PredictiveBackMotion.isImeVisible(layout);
		gestureStarted = imeRoute = false;
		if (hideIme) { motion.finish(); PredictiveBackMotion.hideIme(layout); return; }
		// An IME arriving during a drawer route must not redirect that route to panel close.
		if (PredictiveBackMotion.isImeVisible(layout)) { motion.finish(); return; }
		if (motion.commit(this::close)) return;
		if (!motion.isBusy()) close();
	}
	private void close() {
		if (resumed && layout.isAttachedToWindow() && layout.isDrawerVisible(panel) && unlocked()
				&& !PredictiveBackMotion.isImeVisible(layout)) layout.closeDrawer(panel, false);
	}
	private boolean unlocked() { return layout.getDrawerLockMode(panel) == DrawerLayout.LOCK_MODE_UNLOCKED; }
	private boolean enabled() {
		return resumed && Build.VERSION.SDK_INT >= 33;
	}
	private void schedule() {
		layout.removeCallbacks(register);
		// Register after DrawerLayout's own overlay callback, so this one receives progress too.
		if (enabled()) layout.post(register);
	}
	private void register() {
		unregister();
		if (enabled() && layout.isAttachedToWindow() && layout.isDrawerVisible(panel) && unlocked()) {
			registration = Build.VERSION.SDK_INT >= 34 && InterfaceMotion.isPredictiveBackEnabled()
					? Api34.register(layout, this) : Api33.register(layout, this);
		}
	}
	private void unregister() {
		layout.removeCallbacks(register);
		if (registration != null && Build.VERSION.SDK_INT >= 33) {
			Api33.unregister(registration); registration = null;
		}
	}
	@Override public void onDrawerSlide(View view, float offset) {
		if (view == panel && offset == 0f) { unregister(); reset(); }
	}
	@Override public void onDrawerOpened(View view) { if (view == panel) schedule(); }
	@Override public void onDrawerClosed(View view) { if (view == panel) { unregister(); reset(); } }
	@Override public void onDrawerStateChanged(int state) {
		if (state == DrawerLayout.STATE_DRAGGING) reset();
		if (state == DrawerLayout.STATE_IDLE) schedule();
	}

	@RequiresApi(33)
	private static final class Api33 {
		static Object register(View host, DrawerBackController controller) {
			android.window.OnBackInvokedDispatcher dispatcher = host.findOnBackInvokedDispatcher();
			if (dispatcher == null) return null;
			android.window.OnBackInvokedCallback callback = controller::commit;
			dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
			return new Object[] {dispatcher, callback};
		}
		static void unregister(Object registration) {
			Object[] values = (Object[]) registration;
			((android.window.OnBackInvokedDispatcher) values[0]).unregisterOnBackInvokedCallback(
					(android.window.OnBackInvokedCallback) values[1]);
		}
	}

	@RequiresApi(34)
	private static final class Api34 {
		static Object register(View host, DrawerBackController controller) {
			android.window.OnBackInvokedDispatcher dispatcher = host.findOnBackInvokedDispatcher();
			if (dispatcher == null) return null;
			android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
				@Override public void onBackStarted(android.window.BackEvent event) { controller.start(); }
				@Override public void onBackProgressed(android.window.BackEvent event) { controller.progress(event.getProgress()); }
				@Override public void onBackCancelled() { controller.recover(true); }
				@Override public void onBackInvoked() { controller.commit(); }
			};
			dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
			return new Object[] {dispatcher, callback};
		}
		static void unregister(Object registration) {
			Object[] values = (Object[]) registration;
			((android.window.OnBackInvokedDispatcher) values[0]).unregisterOnBackInvokedCallback(
					(android.window.OnBackInvokedCallback) values[1]);
		}
	}
}
