package com.mishiranu.dashchan.widget;

import android.view.animation.PathInterpolator;

/** Shared presentation timings. Animator applies the system duration scale itself. */
public final class BackMotionSpec {
	private BackMotionSpec() {}
	public static final long RECOVER_MS = 180, COMPLETE_MS = 220, DESTINATION_WAIT_MS = 700;
	public static final float SCREEN_TRAVEL = .35f, SCREEN_INSET = .04f;
	public static final float VERTICAL_RESPONSE = .08f, MAX_VERTICAL = .04f;
	public static final float SCREEN_PARALLAX = .12f, DIALOG_TRAVEL = .35f, DIALOG_SHRINK = .08f, DRAWER_SHRINK = .04f;
	public static final int SCREEN_RADIUS_DP = 24, SCREEN_SCRIM_ALPHA = 30, DIALOG_MAX_TRAVEL_DP = 96;
	public static final int SEARCH_DISMISS_DP = 12, TOOLBAR_TITLE_INSET_DP = 56;
	public static final PathInterpolator EASING = new PathInterpolator(.2f, 0f, 0f, 1f);
	public static float fraction(float value) {
		return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
	}
}
