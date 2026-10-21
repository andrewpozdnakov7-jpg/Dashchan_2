package com.mishiranu.dashchan.widget;

import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.util.OpaqueColors;
import java.util.Arrays;

/** Immutable resolved roles, not a second theme engine. Contains no Context, View or mutable theme. */
public final class AppearanceColors {
	public enum Profile {DRAWER_COMPACT, INTERFACE_COMPATIBILITY, STANDARD_INTERFACE}
	public final Profile profile;
	public final int window, surface, surfaceContainer, onSurface, onSurfaceVariant, primary, onPrimary;
	public final int primaryContainer, onPrimaryContainer, outline, outlineVariant;
	public final int error, errorIcon, errorContainer, onErrorContainer, disabledContent, disabledContainer, ripple;
	public final int unreadContainer, onUnreadContainer;
	private final int[] values;
	private AppearanceColors(Profile profile, Builder b) {
		this.profile = profile;
		window = b.window; surface = b.surface; surfaceContainer = b.surfaceContainer;
		onSurface = b.onSurface; onSurfaceVariant = b.onSurfaceVariant; primary = b.primary; onPrimary = b.onPrimary;
		primaryContainer = b.primaryContainer; onPrimaryContainer = b.onPrimaryContainer;
		outline = b.outline; outlineVariant = b.outlineVariant; error = b.error; errorIcon = b.errorIcon;
		errorContainer = b.errorContainer; onErrorContainer = b.onErrorContainer;
		disabledContent = b.disabledContent; disabledContainer = b.disabledContainer; ripple = b.ripple;
		unreadContainer = b.unreadContainer; onUnreadContainer = b.onUnreadContainer;
		values = new int[]{window, surface, surfaceContainer, onSurface, onSurfaceVariant, primary, onPrimary,
				primaryContainer, onPrimaryContainer, outline, outlineVariant, error, errorIcon, errorContainer,
				onErrorContainer, disabledContent, disabledContainer, ripple, unreadContainer, onUnreadContainer};
	}
	private static final class Builder {
		int window, surface, surfaceContainer, onSurface, onSurfaceVariant, primary, onPrimary;
		int primaryContainer, onPrimaryContainer, outline, outlineVariant, error, errorIcon, errorContainer;
		int onErrorContainer, disabledContent, disabledContainer, ripple, unreadContainer, onUnreadContainer;
	}
	@Override public boolean equals(Object other) {
		return other instanceof AppearanceColors && profile == ((AppearanceColors) other).profile
				&& Arrays.equals(values, ((AppearanceColors) other).values);
	}
	@Override public int hashCode() { return 31 * profile.hashCode() + Arrays.hashCode(values); }

	public static AppearanceColors drawerCompact(ThemeEngine.Theme theme) {
		boolean reference = theme.builtIn && !theme.dynamic && ("Normie".equals(theme.name) || "Neutron".equals(theme.name));
		return drawerCompact(theme.base == ThemeEngine.Theme.Base.DARK, reference, theme.window, theme.card, theme.accent);
	}
	public static AppearanceColors drawerCompact(boolean dark, boolean reference, int window, int card, int accent) {
		Builder b = new Builder();
		b.window = OpaqueColors.over(window, dark ? Color.BLACK : Color.WHITE);
		if (reference) {
			b.surface = dark ? 0xff161c24 : 0xfff3f5fa;
			b.onSurface = dark ? 0xffe4e9f2 : 0xff1c2028;
			b.onSurfaceVariant = dark ? 0xffb7c2d3 : 0xff535e70;
			b.outlineVariant = dark ? 0xff414a59 : 0xffdce1eb;
			b.surfaceContainer = dark ? 0xff2b3442 : 0xffe3e7ef;
			b.disabledContent = dark ? 0xffc4cedd : 0xff394454;
			b.primaryContainer = dark ? 0xff243f63 : 0xffe3edfc;
			b.onPrimaryContainer = dark ? 0xffc5dcff : 0xff285887;
			b.unreadContainer = dark ? 0xff2c4d78 : 0xffc9deff;
			b.onUnreadContainer = dark ? 0xffe0ecff : 0xff123f7a;
			b.errorContainer = dark ? 0xff492f30 : 0xffffedea;
			b.onErrorContainer = dark ? 0xffffb4ab : 0xff8c1d18;
		} else {
			b.surface = window == Color.BLACK ? Color.BLACK : OpaqueColors.over(card, b.window);
			b.onSurface = direct(dark ? 0xffe4e9f2 : 0xff1c2028, b.surface, 4.5);
			b.onSurfaceVariant = direct(OpaqueColors.blend(b.surface, b.onSurface, .7f), b.surface, 4.5);
			b.outlineVariant = OpaqueColors.blend(b.surface, b.onSurface, .18f);
			b.surfaceContainer = OpaqueColors.blend(b.surface, b.onSurface, .09f);
			b.disabledContent = direct(b.onSurfaceVariant, b.surfaceContainer, 4.5);
			int resolvedAccent = OpaqueColors.over(accent, b.surface);
			b.primaryContainer = OpaqueColors.blend(b.surface, resolvedAccent, dark ? .26f : .16f);
			b.onPrimaryContainer = direct(OpaqueColors.over(accent, b.primaryContainer), b.primaryContainer, 4.5);
			b.unreadContainer = OpaqueColors.blend(b.surface, resolvedAccent, dark ? .38f : .26f);
			b.onUnreadContainer = direct(OpaqueColors.over(accent, b.unreadContainer), b.unreadContainer, 4.5);
			b.errorContainer = OpaqueColors.blend(b.surface, dark ? 0xffffb4ab : 0xff8c1d18, .13f);
			b.onErrorContainer = direct(dark ? 0xffffb4ab : 0xff8c1d18, b.errorContainer, 4.5);
		}
		b.primary = OpaqueColors.over(accent, b.surface); b.onPrimary = direct(b.onSurface, b.primary, 4.5);
		b.outline = direct(b.outlineVariant, b.surface, 3);
		b.error = direct(b.onErrorContainer, b.surface, 4.5); b.errorIcon = direct(b.error, b.surface, 3);
		b.disabledContainer = b.surfaceContainer;
		b.ripple = ColorUtils.setAlphaComponent(b.onSurface, 36);
		return new AppearanceColors(Profile.DRAWER_COMPACT, b);
	}
	public static AppearanceColors interfaceCompatibility(boolean dark, int window, int card, int accent, int text, int error) {
		return interfaceProfile(Profile.INTERFACE_COMPATIBILITY, dark, window, card, accent, text, error);
	}
	/** Flat standard pages use the final window as their surface, including a black AMOLED base. */
	public static AppearanceColors standardInterface(boolean dark, int window, int accent, int text, int error) {
		return interfaceProfile(Profile.STANDARD_INTERFACE, dark, window,
				OpaqueColors.over(window, dark ? Color.BLACK : Color.WHITE), accent, text, error);
	}
	private static AppearanceColors interfaceProfile(Profile profile, boolean dark, int window, int card, int accent, int text, int error) {
		Builder b = new Builder();
		b.window = OpaqueColors.over(window, dark ? Color.BLACK : Color.WHITE);
		b.surface = OpaqueColors.over(card, b.window);
		b.onSurface = stepped(text, b.surface, 4.5);
		b.onSurfaceVariant = stepped(OpaqueColors.blend(b.surface, b.onSurface, .72f), b.surface, 4.5);
		b.primary = OpaqueColors.over(accent, b.surface); b.onPrimary = stepped(b.onSurface, b.primary, 4.5);
		b.outline = stepped(OpaqueColors.blend(b.surface, b.onSurface, .5f), b.surface, 3);
		b.outlineVariant = b.outline;
		b.surfaceContainer = OpaqueColors.blend(b.surface, b.primary, .10f);
		b.primaryContainer = b.surfaceContainer; b.onPrimaryContainer = stepped(b.onSurface, b.primaryContainer, 4.5);
		// Preserve the old 3:1 decorative error; the text role is separately 4.5:1.
		b.errorIcon = stepped(error, b.surface, 3); b.error = stepped(error, b.surface, 4.5);
		b.errorContainer = OpaqueColors.blend(b.surface, b.errorIcon, .13f);
		b.onErrorContainer = stepped(error, b.errorContainer, 4.5);
		b.disabledContent = OpaqueColors.blend(b.surface, b.onSurface, .38f);
		b.disabledContainer = OpaqueColors.blend(b.surface, b.primary, .38f);
		b.ripple = ColorUtils.setAlphaComponent(b.onSurface, 36);
		b.unreadContainer = b.primaryContainer; b.onUnreadContainer = b.onPrimaryContainer;
		return new AppearanceColors(profile, b);
	}
	private static int direct(int requested, int background, double minimum) {
		return OpaqueColors.readable(requested, background, minimum, OpaqueColors.Policy.DRAWER_DIRECT);
	}
	private static int stepped(int requested, int background, double minimum) {
		return OpaqueColors.readable(requested, background, minimum, OpaqueColors.Policy.INTERFACE_COMPATIBILITY);
	}
}
