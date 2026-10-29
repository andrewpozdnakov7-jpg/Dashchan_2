package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** One live dismissible surface. Never changes its logical open/closed state during progress. */
public final class PredictiveBackMotion {
	private View view;
	private ValueAnimator animator;
	private boolean tracking, committing;
	private Runnable committedAction;
	private float x, scaleX, scaleY, direction, distance, shrink, progress;
	private final Runnable invalidate;
	private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
	};
	private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> {
		if (r - l != or - ol || b - t != ob - ot) finish();
	};

	public PredictiveBackMotion() { this(() -> {}); }
	public PredictiveBackMotion(Runnable invalidate) { this.invalidate = invalidate; }
	public boolean isTracking() { return tracking; }
	public boolean isBusy() { return view != null; }
	public boolean isCommitting() { return committing; }
	public float getProgress() { return progress; }

	/** The IME owns the first Back; never preview removal of the screen underneath it. */
	public static boolean isImeVisible(View view) {
		WindowInsetsCompat insets = view != null ? ViewCompat.getRootWindowInsets(view) : null;
		return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
	}

	public static void hideIme(View view) {
		android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager)
				view.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
		if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
	}

	public static boolean hasExternalSurface(View view) {
		if (view.getVisibility() != View.VISIBLE) return false;
		if (view instanceof android.view.SurfaceView || view instanceof android.view.TextureView ||
				view instanceof android.webkit.WebView) return true;
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) if (hasExternalSurface(group.getChildAt(i))) return true;
		}
		return false;
	}

	public boolean start(View view, float direction, float distance, float shrink) {
		if (committing) return false;
		finish();
		if (view == null || view.getWidth() == 0 || view.getHeight() == 0 || hasExternalSurface(view) ||
				!ValueAnimator.areAnimatorsEnabled()) return false;
		this.view = view; this.direction = direction; this.distance = distance; this.shrink = shrink;
		x = view.getTranslationX(); scaleX = view.getScaleX(); scaleY = view.getScaleY();
		view.addOnAttachStateChangeListener(attachment); view.addOnLayoutChangeListener(layout);
		tracking = true; apply(0f);
		return true;
	}

	public void progress(float value) {
		if (tracking) apply(Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value)));
	}

	private void apply(float value) {
		progress = value;
		if (view != null) {
			view.setTranslationX(x + direction * distance * value);
			float scale = 1f - shrink * Math.min(1f, value);
			view.setScaleX(scaleX * scale); view.setScaleY(scaleY * scale);
		}
		invalidate.run();
	}

	public void cancel() {
		if (!tracking) return;
		tracking = false; animate(0f, this::finish);
	}

	public boolean commit(Runnable dismiss) {
		if (!tracking) return false;
		tracking = false;
		committing = true;
		committedAction = dismiss;
		float exit = view.getWidth();
		if (view.getParent() instanceof View) {
			View parent = (View) view.getParent();
			exit = Math.max(exit, direction > 0f ? parent.getWidth() - view.getLeft() : view.getRight());
		}
		float end = distance > 0f ? (exit + Math.abs(x) + 1f) / distance : 1f;
		animate(end, this::finish);
		return true;
	}

	private void animate(float end, Runnable completion) {
		if (view == null || !view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
			completion.run(); return;
		}
		animator = ValueAnimator.ofFloat(progress, end);
		animator.setDuration(committing ? BackMotionSpec.COMPLETE_MS : BackMotionSpec.RECOVER_MS);
		animator.setInterpolator(BackMotionSpec.EASING);
		animator.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator a) {
				if (animator == a) { animator = null; completion.run(); }
			}
		});
		animator.start();
	}

	public void finish() {
		// Claim before restoring Views: resize/detach/focus loss must complete an accepted
		// Back exactly once, even when cancellation dispatches animator callbacks inline.
		Runnable action = committedAction;
		committedAction = null;
		tracking = committing = false;
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
		if (view != null) {
			view.removeOnAttachStateChangeListener(attachment); view.removeOnLayoutChangeListener(layout);
			view.setTranslationX(x); view.setScaleX(scaleX); view.setScaleY(scaleY);
			// Pivots and alpha were never changed. In particular, do not turn an implicit
			// layout-dependent pivot into an explicit pivot while restoring this surface.
			view = null;
		}
		progress = 0f; invalidate.run();
		if (action != null) action.run();
	}
}
