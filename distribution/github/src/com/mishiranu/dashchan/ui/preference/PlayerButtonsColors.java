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
		// Float interpolation can truncate alpha 254.99998 to 254 even for two opaque inputs.
		// These semantic surfaces are opaque; translucent ripple/highlight colors are separate.
		return ColorUtils.setAlphaComponent(ColorUtils.blendARGB(first, second, ratio), 255);
	}
	static int contrast(int color, int background, double minimum) {
		// This local helper operates on opaque surfaces, as required by calculateContrast.
		background = ColorUtils.setAlphaComponent(background, 255);
		color = ColorUtils.compositeColors(color, background);
		if (ColorUtils.calculateContrast(color, background) >= minimum) return color;
		int end = ColorUtils.calculateContrast(Color.BLACK, background)
				> ColorUtils.calculateContrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
		for (int i = 1; i <= 100; i++) {
			int next = opaqueBlend(color, end, i / 100f);
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
