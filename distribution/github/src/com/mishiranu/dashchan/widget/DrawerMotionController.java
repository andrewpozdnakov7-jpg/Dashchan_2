package com.mishiranu.dashchan.widget;

import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.RequiresApi;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Visual treatment of an existing drawer; DrawerLayout remains its only state/drag owner. */
public final class DrawerMotionController implements DrawerLayout.DrawerListener {
	private static final int LEGACY_SCRIM = 0x99000000;
	private static final int MODERN_SCRIM_ALPHA = 82;
	private final DrawerLayout layout;
	private final View panel;
	private final View drawerContent;
	private final DrawerAppearance appearance;
	private boolean enabled;
	private boolean tracking;
	private boolean transformed;
	private boolean resumed = true;
	private boolean visible;
	private Object nativeBackRegistration;
	private final Runnable registerBackCallback = this::registerNativeBack;
	private float progress;
	private float originalScaleX, originalScaleY, originalTranslationX, originalPivotX, originalPivotY;
	private ValueAnimator recovery;
	private float slideOffset;
	private View revealContent;
	private float contentAlpha, contentScaleX, contentScaleY, contentTranslationX, contentPivotX, contentPivotY;

	public DrawerMotionController(DrawerLayout layout, View panel, View drawerContent) {
		this.layout = layout;
		this.panel = panel;
		this.drawerContent = drawerContent;
		appearance = new DrawerAppearance(panel);
		layout.addDrawerListener(this);
		layout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) { scheduleBackCallback(); }
			@Override public void onViewDetachedFromWindow(View view) {
				// Detachment is not an Activity lifecycle transition. Allow registration after reattachment.
				unregisterNativeBack();
				reset();
			}
		});
		panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) {
				reset();
				reveal(slideOffset);
			}
		});
		updatePolicy();
	}

	public void updatePolicy() {
		reset();
		unregisterNativeBack();
		enabled = InterfaceMotion.isDrawerEnabled();
		if (layout.isDrawerOpen(panel)) slideOffset = 1f;
		else if (!layout.isDrawerVisible(panel)) slideOffset = 0f;
		appearance.update();
		updateScrim();
		scheduleBackCallback();
	}

	public void resume() {
		resumed = true;
		reveal(slideOffset);
		updateScrim();
		scheduleBackCallback();
	}

	public void suspend() {
		resumed = false;
		unregisterNativeBack();
		reset();
	}

	/** Host calls this after changing DrawerLayout's public lock mode, including item reordering. */
	public void onLockModeChanged() {
		reset();
		unregisterNativeBack();
		scheduleBackCallback();
	}

	private void scheduleBackCallback() {
		layout.removeCallbacks(registerBackCallback);
		// DrawerLayout registers its own PRIORITY_OVERLAY callback after notifying listeners.
		// Register afterwards, at the same priority, without modifying its private callback/state.
		if (enabled && resumed) layout.post(registerBackCallback);
	}

	private void registerNativeBack() {
		unregisterNativeBack();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && enabled && resumed &&
				layout.isAttachedToWindow() && layout.isDrawerVisible(panel) &&
				layout.getDrawerLockMode(panel) == DrawerLayout.LOCK_MODE_UNLOCKED) {
			nativeBackRegistration = Api34Impl.register(layout, this);
		}
	}

	private void unregisterNativeBack() {
		layout.removeCallbacks(registerBackCallback);
		if (nativeBackRegistration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			Api34Impl.unregister(nativeBackRegistration);
			nativeBackRegistration = null;
		}
	}

	public boolean start(boolean fromLeft) {
		reset();
		if (!enabled || !InterfaceMotion.isDrawerEnabled() || !ValueAnimator.areAnimatorsEnabled() ||
				!layout.isDrawerOpen(GravityCompat.START) || panel.getWidth() == 0 || panel.getHeight() == 0 ||
				layout.getDrawerLockMode(panel) != DrawerLayout.LOCK_MODE_UNLOCKED) return false;
		originalScaleX = panel.getScaleX();
		originalScaleY = panel.getScaleY();
		originalTranslationX = panel.getTranslationX();
		originalPivotX = panel.getPivotX();
		originalPivotY = panel.getPivotY();
		boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		panel.setPivotX(rtl ? panel.getWidth() : 0f);
		panel.setPivotY(panel.getHeight() / 2f);
		tracking = transformed = true;
		return true;
	}

	public boolean isTracking() { return tracking; }

	public void progress(float value, boolean fromLeft) {
		if (!tracking) return;
		if (!InterfaceMotion.isDrawerEnabled() || !layout.isDrawerOpen(GravityCompat.START)) {
			reset();
			return;
		}
		float clamped = Math.max(0f, Math.min(1f, value));
		apply(1f - (float) Math.pow(1f - clamped, 3f), fromLeft);
	}

	private void apply(float value, boolean fromLeft) {
		progress = value;
		float distance = 8f * ResourceUtils.obtainDensity(panel) * value;
		panel.setScaleX(originalScaleX * (1f - 0.02f * value));
		panel.setScaleY(originalScaleY * (1f - 0.02f * value));
		panel.setTranslationX(originalTranslationX + (fromLeft ? distance : -distance));
		updateScrim();
	}

	/** Recovery may run together with DrawerLayout's real close, with no fake open/closed events. */
	public void recover(boolean animate) {
		tracking = false;
		if (!transformed) return;
		if (recovery != null) {
			recovery.cancel();
			recovery = null;
		}
		if (animate && panel.isAttachedToWindow() && progress > 0f && ValueAnimator.areAnimatorsEnabled()) {
			boolean fromLeft = panel.getTranslationX() >= originalTranslationX;
			recovery = ValueAnimator.ofFloat(progress, 0f);
			recovery.setDuration(InterfaceMotion.duration(InterfaceMotion.RECOVERY_DURATION));
			recovery.setInterpolator(InterfaceMotion.STANDARD);
			recovery.addUpdateListener(animation -> {
				apply((float) animation.getAnimatedValue(), fromLeft);
				if ((float) animation.getAnimatedValue() == 0f) restoreTransform();
			});
			recovery.start();
		} else {
			restoreTransform();
		}
	}

	private void restoreTransform() {
		if (transformed) {
			panel.setScaleX(originalScaleX);
			panel.setScaleY(originalScaleY);
			panel.setTranslationX(originalTranslationX);
			panel.setPivotX(originalPivotX);
			panel.setPivotY(originalPivotY);
			transformed = false;
		}
		progress = 0f;
		updateScrim();
	}

	public void reset() {
		releaseContent();
		tracking = false;
		if (recovery != null) {
			recovery.cancel();
			recovery = null;
		}
		restoreTransform();
	}

	private void updateScrim() {
		// DrawerLayout already multiplies its scrim by slideOffset. Do not multiply it twice here.
		int alpha = Math.round(MODERN_SCRIM_ALPHA * DrawerMotionSpec.scrimCoefficient(slideOffset)
				* (1f - 0.2f * progress));
		layout.setScrimColor(enabled ? alpha << 24 : LEGACY_SCRIM);
	}

	private void reveal(float offset) {
		if (!enabled || !resumed || !InterfaceMotion.isDrawerEnabled() || !ValueAnimator.areAnimatorsEnabled()
				|| offset <= 0f || offset >= 1f || layout.getDrawerLockMode(panel) != DrawerLayout.LOCK_MODE_UNLOCKED) {
			releaseContent();
			return;
		}
		// Animate the list, not drawerParent: its foreground paints the status-bar inset.
		View child = isInPanel(drawerContent) ? drawerContent : null;
		if (child != revealContent) releaseContent();
		if (child == null) return;
		if (revealContent == null) {
			if (child.getTag(R.id.drawer_motion_owner) != null) return;
			revealContent = child;
			contentAlpha = child.getAlpha(); contentScaleX = child.getScaleX(); contentScaleY = child.getScaleY();
			contentTranslationX = child.getTranslationX(); contentPivotX = child.getPivotX(); contentPivotY = child.getPivotY();
			child.setTag(R.id.drawer_motion_owner, this);
			child.setPivotX(child.getWidth() / 2f); child.setPivotY(child.getHeight() / 2f);
		}
		if (child.getTag(R.id.drawer_motion_owner) != this) { revealContent = null; return; }
		boolean rtl = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		float reveal = DrawerMotionSpec.reveal(offset);
		child.setAlpha(contentAlpha * (0.55f + 0.45f * reveal));
		child.setScaleX(contentScaleX * (0.96f + 0.04f * reveal));
		child.setScaleY(contentScaleY * (0.96f + 0.04f * reveal));
		child.setTranslationX(contentTranslationX + (rtl ? -1f : 1f) * 32f
				* ResourceUtils.obtainDensity(panel) * (1f - reveal));
	}

	private boolean isInPanel(View child) {
		if (child == null) return false;
		for (ViewParent parent = child.getParent(); parent != null; parent = parent.getParent()) {
			if (parent == panel) return true;
		}
		return false;
	}

	private void releaseContent() {
		View child = revealContent;
		revealContent = null;
		if (child != null && child.getTag(R.id.drawer_motion_owner) == this) {
			child.setTag(R.id.drawer_motion_owner, null);
			child.setAlpha(contentAlpha); child.setScaleX(contentScaleX); child.setScaleY(contentScaleY);
			child.setTranslationX(contentTranslationX); child.setPivotX(contentPivotX); child.setPivotY(contentPivotY);
		}
	}

	@Override public void onDrawerSlide(View drawerView, float slideOffset) {
		if (drawerView != panel) return;
		this.slideOffset = Math.max(0f, Math.min(1f, slideOffset));
		reveal(this.slideOffset);
		updateScrim();
		boolean nextVisible = slideOffset > 0f;
		if (visible != nextVisible) {
			visible = nextVisible;
			scheduleBackCallback();
		}
		if (!nextVisible) {
			unregisterNativeBack();
			reset();
		}
	}
	@Override public void onDrawerOpened(View drawerView) {
		if (drawerView == panel) { slideOffset = 1f; releaseContent(); updateScrim(); scheduleBackCallback(); }
	}
	@Override public void onDrawerClosed(View drawerView) {
		if (drawerView == panel) {
			slideOffset = 0f;
			unregisterNativeBack();
			reset();
		}
	}
	@Override public void onDrawerStateChanged(int newState) {
		if (newState == DrawerLayout.STATE_DRAGGING || tracking && newState == DrawerLayout.STATE_SETTLING) {
			reset();
			// Reset predictive Back's panel transform without flashing partially open content.
			reveal(slideOffset);
		}
		if (newState == DrawerLayout.STATE_IDLE) scheduleBackCallback();
	}

	@RequiresApi(34)
	private static final class Api34Impl {
		private static final class Registration {
			final android.window.OnBackInvokedDispatcher dispatcher;
			final android.window.OnBackAnimationCallback callback;
			Registration(android.window.OnBackInvokedDispatcher dispatcher,
					android.window.OnBackAnimationCallback callback) {
				this.dispatcher = dispatcher;
				this.callback = callback;
			}
		}
		static Object register(View host, DrawerMotionController controller) {
			android.window.OnBackInvokedDispatcher dispatcher = host.findOnBackInvokedDispatcher();
			if (dispatcher == null) return null;
			android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
				@Override public void onBackStarted(android.window.BackEvent event) {
					controller.start(event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT);
				}
				@Override public void onBackProgressed(android.window.BackEvent event) {
					controller.progress(event.getProgress(), event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT);
				}
				@Override public void onBackCancelled() { controller.recover(true); }
				@Override public void onBackInvoked() {
					controller.recover(true);
					if (controller.layout.isDrawerVisible(controller.panel) &&
							controller.layout.getDrawerLockMode(controller.panel) == DrawerLayout.LOCK_MODE_UNLOCKED) {
						controller.layout.closeDrawer(controller.panel);
					}
				}
			};
			dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
			return new Registration(dispatcher, callback);
		}
		static void unregister(Object registration) {
			Registration value = (Registration) registration;
			value.dispatcher.unregisterOnBackInvokedCallback(value.callback);
		}
	}
}
