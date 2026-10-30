package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Paint follows the existing enabled flag. Never changes validation, clicks or enabled state. */
final class PostingSendMotion implements View.OnAttachStateChangeListener {
	private final Button button;
	private final GradientDrawable fill = new GradientDrawable();
	private int enabledFill, enabledText, disabledFill, disabledText, currentFill, currentText;
	private boolean enabled, closed;
	private ValueAnimator animator;
	PostingSendMotion(Button button) { this.button = button; button.addOnAttachStateChangeListener(this); }
	void colors(int enabledFill, int enabledText, int disabledFill, int disabledText) {
		finish(); this.enabledFill = enabledFill; this.enabledText = enabledText;
		this.disabledFill = disabledFill; this.disabledText = disabledText;
		float radius = 28 * ResourceUtils.obtainDensity(button);
		fill.setCornerRadius(radius);
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
		button.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(enabledText, 32)), fill, mask));
		settle();
	}
	void bind() {
		if (closed || enabled == button.isEnabled()) return;
		boolean animate = !enabled && button.isEnabled() && button.isAttachedToWindow() && button.isShown()
				&& InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0;
		int startFill = currentFill, startText = currentText;
		finish(); if (!animate) return;
		paint(startFill, startText);
		ValueAnimator running = ValueAnimator.ofFloat(0f, 1f); animator = running;
		running.setDuration(InterfaceMotion.duration(250)); running.setInterpolator(InterfaceMotion.STANDARD);
		running.addUpdateListener(value -> {
			if (animator != value || closed) return;
			float fraction = (float) value.getAnimatedValue();
			paint(ColorUtils.blendARGB(startFill, enabledFill, fraction), ColorUtils.blendARGB(startText, enabledText, fraction));
		});
		running.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) { if (animator == animation) { animator = null; settle(); } }
		});
		running.start();
	}
	private void paint(int background, int text) { currentFill = background; currentText = text; fill.setColor(background); button.setTextColor(text); }
	private void settle() { enabled = button.isEnabled(); paint(enabled ? enabledFill : disabledFill, enabled ? enabledText : disabledText); }
	void finish() {
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
		settle();
	}
	void close() { finish(); closed = true; button.removeOnAttachStateChangeListener(this); }
	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { finish(); }
}
