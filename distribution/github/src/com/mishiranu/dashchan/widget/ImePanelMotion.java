package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.List;

/** Tracks the system IME timeline; never resizes the editor or requests keyboard/cursor actions. */
public final class ImePanelMotion extends WindowInsetsAnimation.Callback implements View.OnAttachStateChangeListener {
	private final View root, panel;
	private WindowInsetsAnimation active;
	private float before, distance, originalY;
	private boolean disposed;
	private ImePanelMotion(View root, View panel) {
		super(DISPATCH_MODE_CONTINUE_ON_SUBTREE); this.root = root; this.panel = panel;
		root.setTag(R.id.ime_panel_motion_owner, this); root.setWindowInsetsAnimationCallback(this); root.addOnAttachStateChangeListener(this);
	}
	public static void attach(View root, View panel) {
		release(root); if (root != null && panel != null) new ImePanelMotion(root, panel);
	}
	public static void finish(View root) {
		Object tag = root != null ? root.getTag(R.id.ime_panel_motion_owner) : null;
		if (tag instanceof ImePanelMotion) ((ImePanelMotion) tag).settle();
	}
	public static void release(View root) {
		Object tag = root != null ? root.getTag(R.id.ime_panel_motion_owner) : null;
		if (tag instanceof ImePanelMotion) ((ImePanelMotion) tag).dispose();
	}
	private boolean valid() {
		return !disposed && root.getTag(R.id.ime_panel_motion_owner) == this && root.isAttachedToWindow() && root.hasWindowFocus()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && !ElementMotion.busy(root) && !ElementMotion.busy(panel);
	}
	private float position() { int[] location = new int[2]; panel.getLocationInWindow(location); return location[1]; }
	@Override public void onPrepare(WindowInsetsAnimation animation) {
		if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
		float presented = position(); settle();
		if (valid()) { active = animation; originalY = panel.getTranslationY(); before = presented; }
	}
	@Override public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) {
		if (active == animation) {
			if (!valid()) settle();
			else { distance = before - position(); panel.setTranslationY(originalY + distance); }
		}
		return bounds;
	}
	@Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
		if (active != null) {
			if (!valid() || !running.contains(active)) settle();
			else panel.setTranslationY(originalY + distance * (1f - active.getInterpolatedFraction()));
		}
		return insets;
	}
	@Override public void onEnd(WindowInsetsAnimation animation) { if (active == animation) settle(); }
	private void settle() {
		if (active != null && root.getTag(R.id.ime_panel_motion_owner) == this && !ElementMotion.busy(panel)) panel.setTranslationY(originalY);
		active = null; distance = 0f;
	}
	private void dispose() {
		if (disposed) return; settle(); disposed = true; root.removeOnAttachStateChangeListener(this);
		if (root.getTag(R.id.ime_panel_motion_owner) == this) { root.setTag(R.id.ime_panel_motion_owner, null); root.setWindowInsetsAnimationCallback(null); }
	}
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { dispose(); }
}
