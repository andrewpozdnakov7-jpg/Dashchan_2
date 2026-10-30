package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Color;
import com.mishiranu.dashchan.util.OpaqueColors;

/** Toolbar-only resolved colors. No cache, global role changes or geometry ownership. */
final class ToolbarPalette {
	final int background, title, subtitle, icon;
	private ToolbarPalette(int background, int title, int subtitle, int icon) {
		this.background = background; this.title = title; this.subtitle = subtitle; this.icon = icon;
	}
	static ToolbarPalette resolve(Context context) {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		return resolve(theme.base == ThemeEngine.Theme.Base.DARK,
				StandardAppearance.colors(context).surface, theme.accent);
	}
	static ToolbarPalette resolve(boolean dark, int surface, int accent) {
		int backing = OpaqueColors.over(surface, dark ? Color.BLACK : Color.WHITE);
		int a = OpaqueColors.over(accent, backing);
		int background = !dark ? mix(Color.WHITE, a, 12)
				: backing == Color.BLACK ? Color.BLACK : mix(backing, a, 10);
		int title = mix(dark ? 0xfff1f3f4 : 0xff202124, a, dark ? 10 : 15);
		int subtitle = mix(dark ? 0xffb7bdc6 : 0xff5b6069, a, 15);
		int icon = mix(dark ? 0xffd0d5dd : 0xff485160, a, dark ? 20 : 25);
		return new ToolbarPalette(background, readable(title, background, 4.5),
				readable(subtitle, background, 4.5), readable(icon, background, 3));
	}
	private static int readable(int color, int background, double contrast) {
		return OpaqueColors.readable(color, background, contrast, OpaqueColors.Policy.INTERFACE_COMPATIBILITY);
	}
	// Encoded sRGB, integer percent, nearest integer with halves rounded up.
	private static int channel(int x, int y, int percent) { return (x * (100 - percent) + y * percent + 50) / 100; }
	private static int mix(int x, int y, int percent) {
		return Color.rgb(channel(Color.red(x), Color.red(y), percent),
				channel(Color.green(x), Color.green(y), percent), channel(Color.blue(x), Color.blue(y), percent));
	}
}
