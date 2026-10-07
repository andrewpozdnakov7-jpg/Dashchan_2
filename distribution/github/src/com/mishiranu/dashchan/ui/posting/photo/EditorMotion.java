package com.mishiranu.dashchan.ui.posting.photo;

import android.animation.ValueAnimator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/** Presentation policy only. The platform applies its duration scale exactly once. */
public final class EditorMotion {
    private EditorMotion() {}
    public static final long ROTATE = 270, CROP = 270, RATIO = 220, RESET = 250, HISTORY = 220;
    public static final long MIRROR = 160, PIXELS = 120, PANELS = 180, SELECTION = 120;
    public static final long GRID_IN = 75, GRID_OUT = 150, MAX_RETARGET = 420;
    public static final int PANEL_PIXELS = 400000;
    public static final int SCENE_SIDE = 1536;
    public static final Interpolator GEOMETRY = new PathInterpolator(.2f, 0f, 0f, 1f);
    public static final Interpolator FADE = new LinearInterpolator();
    public static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }
}
