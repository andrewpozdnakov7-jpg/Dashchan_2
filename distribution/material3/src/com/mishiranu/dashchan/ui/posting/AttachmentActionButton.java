package com.mishiranu.dashchan.ui.posting;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Paint-only feedback: native touch/click routing and the48dp layout bounds never change. */
final class AttachmentActionButton extends AppCompatImageView {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final GradientDrawable rippleMask = new GradientDrawable();
	private final int tone, disabledTone;
	private float pressProgress;
	private boolean pressedShape;
	private ValueAnimator feedback;

	AttachmentActionButton(Context context, int surface, int accent, int ink) {
		super(context);
		tone = PostingAttachmentsAppearance.opaqueBlend(surface, accent, .13f);
		disabledTone = PostingAttachmentsAppearance.opaqueBlend(surface, ink, .04f);
		int foreground = PostingAttachmentsAppearance.contrasting(ink, tone, 4.5);
		setImageTintList(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
				new int[] {ColorUtils.setAlphaComponent(foreground, 96), foreground}));
		setPadding(dp(14), dp(14), dp(14), dp(14));
		setMinimumWidth(dp(48)); setMinimumHeight(dp(48)); setFocusable(true);
		rippleMask.setColor(Color.WHITE); rippleMask.setCornerRadius(dp(12));
		// Only the36dp visual surface ripples. The surrounding6dp still belongs to
		// this button's native hit area, not the adjacent filename or other action.
		setForeground(new InsetDrawable(new RippleDrawable(
				ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 32)), null, rippleMask), dp(6)));
	}
	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(this) * value); }
	@Override public CharSequence getAccessibilityClassName() { return "android.widget.ImageButton"; }

	@Override protected void onDraw(Canvas canvas) {
		float scale = 1f - .06f * pressProgress;
		int saved = canvas.save();
		canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
		paint.setColor(isEnabled() ? tone : disabledTone);
		float inset = dp(6), radius = dp(12) + dp(6) * pressProgress;
		canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, paint);
		super.onDraw(canvas);
		canvas.restoreToCount(saved);
	}
	@Override protected void drawableStateChanged() {
		super.drawableStateChanged();
		if (paint == null) return; // View's constructor can dispatch before our fields exist.
		boolean next = isEnabled() && isPressed();
		if (next != pressedShape) {
			pressedShape = next;
			animatePress(next);
		}
		invalidate();
	}
	private void animatePress(boolean pressed) {
		cancelFeedback();
		float target = pressed ? 1f : 0f;
		int duration = InterfaceMotion.duration(pressed ? 100 : 300);
		if (!isAttachedToWindow() || !isShown() || !hasWindowFocus() || !isEnabled()
				|| !InterfaceMotion.isEnabled() || duration == 0) {
			setProgress(target); return;
		}
		ValueAnimator animator = ValueAnimator.ofFloat(pressProgress, target);
		feedback = animator;
		animator.setDuration(duration);
		animator.setInterpolator(pressed ? InterfaceMotion.STANDARD : new OvershootInterpolator(1.2f));
		animator.addUpdateListener(value -> setProgress((float) value.getAnimatedValue()));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (feedback == animation) { feedback = null; setProgress(target); }
			}
		});
		animator.start();
	}
	private void setProgress(float value) {
		pressProgress = value;
		if (rippleMask != null) rippleMask.setCornerRadius(dp(12) + dp(6) * value);
		invalidate();
	}
	private void cancelFeedback() {
		if (feedback != null) {
			ValueAnimator old = feedback; feedback = null;
			old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel();
		}
	}
	private void resetFeedback() {
		cancelFeedback(); pressedShape = false; setProgress(0);
		if (getForeground() != null) getForeground().jumpToCurrentState();
	}
	@Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); resetFeedback(); }
	@Override public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus && paint != null) resetFeedback();
	}
	@Override protected void onVisibilityChanged(View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		if (visibility != VISIBLE && paint != null) resetFeedback();
	}
}
