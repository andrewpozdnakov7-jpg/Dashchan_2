package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

/** Fraction-driven local presentation. The caller alone owns the logical Back action. */
public final class PredictiveTransition {
	public interface Presentation {
		boolean valid();
		void apply(float fraction);
		void restore();
	}
	private final View host;
	private final Presentation presentation;
	private ValueAnimator animator;
	private boolean tracking = true, released, committing;
	private float fraction;
	private Runnable accepted;
	private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
	};
	private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> {
		if (r - l != or - ol || b - t != ob - ot) finish();
	};
	public PredictiveTransition(View host, Presentation presentation) {
		this.host = host; this.presentation = presentation;
		host.addOnAttachStateChangeListener(attachment); host.addOnLayoutChangeListener(layout);
		presentation.apply(0f);
	}
	public boolean isBusy() { return !released; }
	public boolean isCommitting() { return committing && !released; }
	public boolean isTracking() { return tracking && !released; }
	public float getFraction() { return fraction; }
	public void progress(float value) {
		if (!isTracking()) return;
		if (!presentation.valid()) { finish(); return; }
		apply(BackMotionSpec.fraction(value));
	}
	private void apply(float value) { fraction = value; presentation.apply(value); }
	public void cancel() {
		if (!isTracking()) return;
		tracking = false; animate(0f, BackMotionSpec.RECOVER_MS);
	}
	public boolean commit(Runnable action) {
		if (!isTracking() || !presentation.valid()) { finish(); return false; }
		tracking = false; committing = true; accepted = action;
		animate(1f, BackMotionSpec.COMPLETE_MS); return true;
	}
	private void animate(float end, long duration) {
		if (!host.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { apply(end); finish(); return; }
		ValueAnimator next = ValueAnimator.ofFloat(fraction, end); animator = next;
		next.setDuration(duration); next.setInterpolator(BackMotionSpec.EASING);
		next.addUpdateListener(value -> {
			if (!presentation.valid()) { finish(); return; }
			apply((float) value.getAnimatedValue());
		});
		next.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) { if (animator == animation) finish(); }
		});
		next.start();
	}
	public void finish() {
		if (released) return;
		released = true; tracking = false;
		Runnable action = accepted; accepted = null;
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllListeners(); old.removeAllUpdateListeners(); old.cancel(); }
		host.removeOnAttachStateChangeListener(attachment); host.removeOnLayoutChangeListener(layout);
		presentation.restore();
		// Teardown after acceptance completes once; before acceptance it only restores.
		if (action != null) action.run();
	}
}
