package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;

/** Local semantic roles, derived from the user's palette, not the reference SVG palette. */
final class PlayerButtonsColors {
	final int background, card, panel, ink, secondary, primary, active, outline, error;
	PlayerButtonsColors(Context context) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		background = ColorUtils.setAlphaComponent(theme.window, 255);
		int text = ResourceUtils.getColor(context, android.R.attr.textColorPrimary);
		int initialInk = contrast(text, background, 4.5);
		card = ColorUtils.blendARGB(background, initialInk, 0.045f);
		panel = ColorUtils.blendARGB(background, initialInk, 0.10f);
		ink = contrast(initialInk, panel, 4.5);
		secondary = contrast(ResourceUtils.getColor(context, android.R.attr.textColorSecondary), card, 4.5);
		primary = contrast(theme.accent, card, 4.5);
		active = ColorUtils.blendARGB(card, primary, 0.15f);
		outline = contrast(ColorUtils.blendARGB(card, ink, 0.42f), card, 3);
		error = contrast(ResourceUtils.getColor(context, android.R.attr.colorError), card, 3);
	}
	static int contrast(int color, int background, double minimum) {
		color = ColorUtils.compositeColors(color, background);
		if (ColorUtils.calculateContrast(color, background) >= minimum) return color;
		int end = ColorUtils.calculateContrast(Color.BLACK, background)
				> ColorUtils.calculateContrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
		for (int i = 1; i <= 100; i++) {
			int next = ColorUtils.blendARGB(color, end, i / 100f);
			if (ColorUtils.calculateContrast(next, background) >= minimum) return next;
		}
		return end;
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
