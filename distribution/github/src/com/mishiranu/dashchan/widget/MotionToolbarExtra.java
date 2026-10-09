package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Measures children at their final height; only the container height and drawing are transient. */
public final class MotionToolbarExtra extends FrameLayout {
	private LayoutTransition legacy;
	private ValueAnimator animator;
	private Bitmap previous;
	private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private float height = -1f, progress = 1f;
	private int target = -1, width = -1, naturalHeight;
	public MotionToolbarExtra(Context context, AttributeSet attrs) { super(context, attrs); }
	@Override public void setLayoutTransition(LayoutTransition transition) {
		legacy = transition; super.setLayoutTransition(InterfaceMotion.isEnabled() ? null : transition);
	}
	public void updatePolicy() {
		finishPresentation(); super.setLayoutTransition(InterfaceMotion.isEnabled() ? null : legacy);
	}
	private boolean enabled() {
		return InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && isAttachedToWindow()
				&& getTag(R.id.screen_motion_owner) == null;
	}
	private void capturePrevious() {
		if (previous != null || !enabled() || getWidth() <= 0 || getHeight() <= 0) return;
		float scale = Math.min(1f, (float) Math.sqrt(180000d / ((long) getWidth() * getHeight())));
		try {
			previous = Bitmap.createBitmap(Math.max(1, (int) (getWidth() * scale)), Math.max(1, (int) (getHeight() * scale)), Bitmap.Config.ARGB_8888);
			previousDisplayHeight = getHeight();
			Canvas canvas = new Canvas(previous); canvas.scale((float) previous.getWidth() / getWidth(), (float) previous.getHeight() / getHeight());
			super.dispatchDraw(canvas);
		} catch (RuntimeException | OutOfMemoryError error) { recyclePrevious(); }
	}
	@Override public void addView(View child, int index, android.view.ViewGroup.LayoutParams params) {
		capturePrevious(); super.addView(child, index, params);
	}
	@Override public void removeView(View child) { capturePrevious(); super.removeView(child); }
	@Override public void removeAllViews() { capturePrevious(); super.removeAllViews(); }
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		super.onMeasure(widthSpec, heightSpec);
		naturalHeight = getMeasuredHeight(); int measuredWidth = getMeasuredWidth();
		if (!enabled() || width != measuredWidth || target < 0) {
			finishPresentation(); width = measuredWidth; target = naturalHeight;
			return;
		}
		if (target != naturalHeight || previous != null && animator == null) {
			float from = height >= 0 ? height : getHeight();
			if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
			target = naturalHeight; height = from; progress = 0f;
			ValueAnimator next = ValueAnimator.ofFloat(0f, 1f); animator = next;
			next.setDuration(InterfaceMotion.duration(240)); next.setInterpolator(new android.view.animation.LinearInterpolator());
			next.addUpdateListener(value -> {
				if (animator != value) return;
				if (!enabled()) { finishPresentation(); return; }
				progress = (float) value.getAnimatedValue(); height = from + (target - from) * InterfaceMotion.STANDARD.getInterpolation(progress);
				requestLayout(); invalidate();
			});
			next.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationEnd(Animator animation) { if (animator == animation) finishPresentation(); }
			});
			// No animator start or child mutation inside a measure traversal.
			removeCallbacks(startAnimation); post(startAnimation);
		}
		if (height >= 0) setMeasuredDimension(measuredWidth, resolveSize(Math.max(0, Math.round(height)), heightSpec));
	}
	private final Runnable startAnimation = () -> { if (animator != null) animator.start(); };
	@Override protected void dispatchDraw(Canvas canvas) {
		if (!enabled()) { if (animator != null || previous != null) finishPresentation(); super.dispatchDraw(canvas); return; }
		if (animator == null) { super.dispatchDraw(canvas); return; }
		int clip = canvas.save(); canvas.clipRect(0, 0, getWidth(), getHeight());
		float incoming = Math.max(0f, Math.min((progress - .3f) / .7f, 1f));
		int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), Math.max(getHeight(), naturalHeight), Math.round(255f * incoming));
		canvas.translate(0f, 8f * ResourceUtils.obtainDensity(this) * (1f - incoming));
		super.dispatchDraw(canvas); canvas.restoreToCount(save);
		if (previous != null) {
			paint.setAlpha(Math.round(255f * (1f - Math.min(progress / .3f, 1f))));
			canvas.drawBitmap(previous, null, new Rect(0, 0, getWidth(), previousDisplayHeight), paint);
		}
		canvas.restoreToCount(clip);
	}
	private int previousDisplayHeight;
	private void recyclePrevious() { if (previous != null) { previous.recycle(); previous = null; } }
	public void finishPresentation() {
		boolean changed = animator != null || previous != null || height >= 0f;
		removeCallbacks(startAnimation);
		if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
		recyclePrevious(); height = -1f; progress = 1f;
		if (changed) { requestLayout(); invalidate(); }
	}
	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		return getTag(R.id.screen_motion_owner) != null || animator != null || super.dispatchTouchEvent(event);
	}
	@Override protected void onDetachedFromWindow() { finishPresentation(); super.onDetachedFromWindow(); }
}
