package com.mishiranu.dashchan.util;

import android.animation.Animator;
import android.os.Build;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.content.Preferences;
import java.util.function.BooleanSupplier;

/** F-Droid presentation contract. Experimental rendering is not part of this source set. */
public final class InterfaceMotion {
	public static final int SCREEN_DURATION = 260;
	public static final int FADE_DURATION = 160;
	public static final int RECOVERY_DURATION = 180;
	public static final int GALLERY_DURATION = 260;
	public static final Interpolator STANDARD = new LinearInterpolator();

	private InterfaceMotion() {}

	public static boolean isEnabled() { return false; }
	public static boolean isDrawerEnabled() { return false; }

	// The separately configured legacy predictive Back is not an experimental motion feature.
	public static boolean isPredictiveBackEnabled() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Preferences.isPredictiveBackEnabled();
	}

	public static int duration(int duration) { return 0; }
	public static void cancel(View view) {}
	public static boolean isAnimating(View view) { return false; }
	public static Animator content(View view, boolean enter, int transition) { return null; }
	public static Animator reveal(View view) { return null; }
	public static OneShotPreDrawListener revealBeforeDraw(View view, View transitionRoot,
			BooleanSupplier isValid) { return null; }
}
