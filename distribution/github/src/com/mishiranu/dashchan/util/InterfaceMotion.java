package com.mishiranu.dashchan.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import androidx.fragment.app.FragmentTransaction;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import java.util.function.BooleanSupplier;

/** Shared presentation policy. It must never commit navigation or change content state. */
public final class InterfaceMotion {
	public static final int SCREEN_DURATION = 260;
	public static final int FADE_DURATION = 160;
	public static final int RECOVERY_DURATION = 180;
	public static final int GALLERY_DURATION = 260;
	public static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

	private InterfaceMotion() {}

	public static boolean isEnabled() {
		return Preferences.isNewInterfaceMotionEnabled();
	}

	public static boolean isDrawerEnabled() {
		return isEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
	}

	public static boolean isPredictiveBackEnabled() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				(Preferences.isPredictiveBackEnabled() || isDrawerEnabled());
	}

	public static int duration(int duration) {
		return ValueAnimator.areAnimatorsEnabled() ? duration : 0;
	}

	/** Cancels only an animator owned by this policy, never an unrelated widget animator. */
	public static void cancel(View view) {
		if (view != null) {
			Object tag = view.getTag(R.id.interface_motion_animator);
			if (tag instanceof Animator) {
				((Animator) tag).cancel();
				// An animator cancelled before start does not dispatch its end listener.
				if (view.getTag(R.id.interface_motion_animator) == tag) {
					view.setTag(R.id.interface_motion_animator, null);
					view.setAlpha(1f);
					view.setTranslationX(0f);
				}
			}
		}
	}

	public static boolean isAnimating(View view) {
		Object tag = view != null ? view.getTag(R.id.interface_motion_animator) : null;
		// Ownership covers preparation and startDelay as well, not only running child animators.
		return tag instanceof Animator || view != null && (view.getTag(R.id.thread_motion_owner) != null
				|| view.getTag(R.id.screen_motion_owner) != null);
	}

	private static Animator own(View view, Animator animator, boolean resetOnEnd) {
		view.setTag(R.id.interface_motion_animator, animator);
		animator.addListener(new AnimatorListenerAdapter() {
			private boolean cancelled;
			@Override public void onAnimationCancel(Animator animation) { cancelled = true; }
			@Override public void onAnimationEnd(Animator animation) {
				if (view.getTag(R.id.interface_motion_animator) == animation) {
					view.setTag(R.id.interface_motion_animator, null);
					if (resetOnEnd || cancelled) {
						view.setAlpha(1f);
						view.setTranslationX(0f);
					}
				}
			}
		});
		return animator;
	}

	public static Animator content(View view, boolean enter, int transition) {
		if (view == null) return null;
		cancel(view);
		boolean section = transition == FragmentTransaction.TRANSIT_FRAGMENT_FADE;
		boolean back = transition == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE;
		float direction = back ? -1f : 1f;
		if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
		float distance = section ? 0f : direction * 24f * ResourceUtils.obtainDensity(view);
		if (enter) {
			// Keep a delayed section entrance hidden; otherwise it flashes before the fade starts.
			view.setAlpha(0f);
			view.setTranslationX(distance);
		}
		ObjectAnimator alpha = ObjectAnimator.ofFloat(view, View.ALPHA, enter ? 0f : view.getAlpha(),
				enter ? 1f : 0f);
		alpha.setDuration(duration(enter ? FADE_DURATION : 90));
		ObjectAnimator position = ObjectAnimator.ofFloat(view, View.TRANSLATION_X,
				enter ? distance : view.getTranslationX(), enter ? 0f : -distance / 3f);
		position.setDuration(duration(SCREEN_DURATION));
		AnimatorSet set = new AnimatorSet();
		set.playTogether(alpha, position);
		set.setInterpolator(STANDARD);
		if (enter && section) set.setStartDelay(duration(70));
		return own(view, set, enter);
	}

	/** Decide after the root transition is prepared, but before the ready content can be drawn. */
	public static OneShotPreDrawListener revealBeforeDraw(View view, View transitionRoot,
			BooleanSupplier isValid) {
		return OneShotPreDrawListener.add(view, () -> {
			if (isValid.getAsBoolean() && !isAnimating(transitionRoot) && !isAnimating(view)) {
				reveal(view).start();
			}
		});
	}

	public static Animator reveal(View view) {
		if (view == null) return null;
		cancel(view);
		// Set the first frame synchronously; ObjectAnimator may apply it on a later animation pulse.
		view.setAlpha(0f);
		ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
		animator.setDuration(duration(FADE_DURATION));
		animator.setInterpolator(STANDARD);
		return own(view, animator, true);
	}
}
