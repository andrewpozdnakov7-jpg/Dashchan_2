package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.OpaqueColors;
import com.mishiranu.dashchan.widget.ThemeEngine;

/** Local semantic roles, derived from the user's palette, not the reference SVG palette. */
final class PlayerButtonsColors {
	final int background, card, panel, cell, ink, secondary, primary, active, outline, divider, error, disabled;
	PlayerButtonsColors(Context context) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		background = ColorUtils.setAlphaComponent(theme.window, 255);
		int text = ResourceUtils.getColor(context, android.R.attr.textColorPrimary);
		int initialInk = contrast(text, background, 4.5);
		int accent = ColorUtils.setAlphaComponent(theme.accent, 255);
		card = opaqueBlend(background, accent, 0.075f);
		panel = opaqueBlend(card, accent, 0.055f);
		cell = opaqueBlend(panel, accent, 0.075f);
		ink = contrast(contrast(initialInk, panel, 4.5), cell, 4.5);
		disabled = contrast(contrast(Color.GRAY, panel, 3), cell, 3);
		secondary = contrast(contrast(ResourceUtils.getColor(context, android.R.attr.textColorSecondary), card, 4.5), background, 4.5);
		primary = contrast(contrast(theme.accent, card, 4.5), background, 4.5);
		active = opaqueBlend(card, primary, 0.15f);
		outline = contrast(opaqueBlend(card, ink, 0.42f), card, 3);
		divider = opaqueBlend(background, ink, 0.12f);
		error = contrast(ResourceUtils.getColor(context, android.R.attr.colorError), card, 3);
	}
	static int opaqueBlend(int first, int second, float ratio) {
		return OpaqueColors.blend(first, second, ratio);
	}
	static int contrast(int color, int background, double minimum) {
		return OpaqueColors.readable(color, background, minimum, OpaqueColors.Policy.PLAYER_COMPATIBILITY);
	}
	static GradientDrawable shape(int color, float radius) {
		GradientDrawable result = new GradientDrawable();
		result.setColor(color); result.setCornerRadius(radius);
		return result;
	}
	RippleDrawable ripple(int color, float radius) {
		return new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 36)),
				shape(color, radius), shape(Color.WHITE, radius));
	}
}
