package com.mishiranu.dashchan.util;

import android.graphics.Color;
import androidx.core.graphics.ColorUtils;

/** Arithmetic for final opaque roles only. Ripple, scrim and video overlays are NOT opaque roles. */
public final class OpaqueColors {
	public enum Policy {
		DRAWER_DIRECT(0, true), INTERFACE_COMPATIBILITY(20, true), PLAYER_COMPATIBILITY(100, false);
		final int steps; final boolean blackOnTie;
		Policy(int steps, boolean blackOnTie) { this.steps = steps; this.blackOnTie = blackOnTie; }
	}
	private OpaqueColors() {}
	/** Inputs represent already resolved opaque roles; normalize the float-interpolated alpha. */
	public static int blend(int first, int second, float ratio) {
		return ColorUtils.setAlphaComponent(ColorUtils.blendARGB(first, second, ratio), 255);
	}
	/** Background is a known final backing surface. Resolve genuinely translucent backings first. */
	public static int over(int foreground, int background) {
		return ColorUtils.compositeColors(foreground, ColorUtils.setAlphaComponent(background, 255));
	}
	public static double contrast(int foreground, int background) {
		int opaque = ColorUtils.setAlphaComponent(background, 255);
		return ColorUtils.calculateContrast(over(foreground, opaque), opaque);
	}
	public static int readable(int requested, int background, double minimum, Policy policy) {
		if (!(minimum >= 1 && minimum <= 4.5)) throw new IllegalArgumentException("Unsupported contrast target");
		background = ColorUtils.setAlphaComponent(background, 255);
		int foreground = over(requested, background);
		if (contrast(foreground, background) >= minimum) return foreground;
		double black = contrast(Color.BLACK, background), white = contrast(Color.WHITE, background);
		int end = (black > white || policy.blackOnTie && black == white) ? Color.BLACK : Color.WHITE;
		for (int i = 1; i <= policy.steps; i++) {
			int candidate = blend(foreground, end, i / (float) policy.steps);
			if (contrast(candidate, background) >= minimum) return candidate;
		}
		return end;
	}
}
