package com.mishiranu.dashchan.widget;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewParent;
import androidx.drawerlayout.widget.DrawerLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Visual treatment of an existing drawer; DrawerLayout remains its only state/drag owner. */
public final class DrawerMotionController implements DrawerLayout.DrawerListener {
	private static final int LEGACY_SCRIM_ALPHA = 153;
	private static final int MODERN_SCRIM_ALPHA = 82;
	private final DrawerLayout layout;
	private final View panel;
	private final View drawerContent;
	private final DrawerAppearance appearance;
	private boolean enabled;
	private boolean resumed = true;
	private final DrawerBackController back;
	private float slideOffset;
	private View revealContent;
	private float contentAlpha, contentScaleX, contentScaleY, contentTranslationX, contentPivotX, contentPivotY;

	public DrawerMotionController(DrawerLayout layout, View panel, View drawerContent) {
		this.layout = layout;
		this.panel = panel;
		this.drawerContent = drawerContent;
		appearance = new DrawerAppearance(panel);
		back = new DrawerBackController(layout, panel, this::updateScrim);
		layout.addDrawerListener(this);
		layout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { reset(); }
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
		back.update();
		enabled = InterfaceMotion.isDrawerEnabled();
		if (layout.isDrawerOpen(panel)) slideOffset = 1f;
		else if (!layout.isDrawerVisible(panel)) slideOffset = 0f;
		appearance.update();
		updateScrim();
	}

	public void resume() {
		resumed = true;
		back.resume();
		reveal(slideOffset);
		updateScrim();
	}

	public void suspend() {
		resumed = false;
		back.suspend();
		reset();
	}

	/** Host calls this after changing DrawerLayout's public lock mode, including item reordering. */
	public void onLockModeChanged() {
		reset();
		back.update();
	}

	public boolean start(boolean fromLeft) { return back.start(); }
	public boolean isTracking() { return back.isTracking(); }
	public boolean isBusy() { return back.isBusy(); }
	public void progress(float value, boolean fromLeft) { back.progress(value); }
	public void recover(boolean animate) { back.recover(animate); }
	public boolean commitBack() {
		if (back.hasConsumedInvocation()) return true;
		if (back.isTracking()) { back.commit(); return true; }
		if (back.isBusy()) { back.commit(); return true; }
		return false;
	}
	public void reset() { releaseContent(); back.reset(); }

	private void updateScrim() {
		// DrawerLayout already multiplies its scrim by slideOffset. Do not multiply it twice here.
		int alpha = Math.round(MODERN_SCRIM_ALPHA * DrawerMotionSpec.scrimCoefficient(slideOffset)
				* (1f - 0.8f * back.progress()));
		layout.setScrimColor((enabled ? alpha : Math.round(LEGACY_SCRIM_ALPHA * (1f - .8f * back.progress()))) << 24);
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
	}
	@Override public void onDrawerOpened(View drawerView) {
		if (drawerView == panel) { slideOffset = 1f; releaseContent(); updateScrim(); }
	}
	@Override public void onDrawerClosed(View drawerView) {
		if (drawerView == panel) {
			slideOffset = 0f;
			back.update();
			reset();
		}
	}
	@Override public void onDrawerStateChanged(int newState) {
		if (newState == DrawerLayout.STATE_DRAGGING || back.isTracking() && newState == DrawerLayout.STATE_SETTLING) {
			reset();
			// Reset predictive Back's panel transform without flashing partially open content.
			reveal(slideOffset);
		}
	}

}
