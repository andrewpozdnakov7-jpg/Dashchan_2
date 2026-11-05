package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

/** Drawer-local tokens. No global theme change or Material dependency in shared sources. */
public final class DrawerStyle {
	public enum Role {TITLE, GROUP, THREAD, MENU}
	public static final int MIN_ROW_HEIGHT = 48;
	// Drawer visual-polish V1 dimensions, not global Material defaults.
	public static final int CHIP_MIN_WIDTH = 64, CHIP_MIN_HEIGHT = 28;
	public static final int CHIP_PADDING = 6, CHIP_ICON = 16, CHIP_ICON_GAP = 4;
	public static final int THREAD_END = 8, MENU_ICON = 22, MENU_ICON_GAP = 24, COUNT_GAP = 6;
	public final AppearanceColors colors;
	public final int surface, onSurface, variant, outline;
	public final int neutral, onNeutral, active, onActive, unread, onUnread, error, onError;
	public final int chipWidth, chipHeight, rowHeight;
	public final float counterTextSize;
	public final Typeface counterTypeface;
	public final int textScalePercent;
	private final Context context;

	public static int surface(Context context) {
		return Preferences.isNewInterfaceMotionEnabled() ? new DrawerStyle(context).surface
				: ThemeEngine.getTheme(context).card;
	}

	public DrawerStyle(Context context) { this(context, Preferences.getDrawerTextScalePercent()); }
	public DrawerStyle(Context context, int textScalePercent) {
		this.context = context;
		this.textScalePercent = Math.max(Preferences.MIN_TEXT_SCALE, Math.min(textScalePercent, Preferences.MAX_TEXT_SCALE));
		ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
		colors = AppearanceColors.drawerCompact(theme);
		surface = colors.surface; onSurface = colors.onSurface; variant = colors.onSurfaceVariant;
		outline = colors.outlineVariant; neutral = colors.surfaceContainer; onNeutral = colors.disabledContent;
		active = colors.primaryContainer; onActive = colors.onPrimaryContainer;
		unread = colors.unreadContainer; onUnread = colors.onUnreadContainer;
		error = colors.errorContainer; onError = colors.onErrorContainer;
		TextView probe = new TextView(context);
		apply(probe, Role.MENU);
		AppearanceTypography.apply(probe, AppearanceTypography.Role.DRAWER_COUNTER);
		scaleText(probe, this.textScalePercent);
		probe.setFontFeatureSettings("tnum");
		counterTextSize = probe.getTextSize();
		counterTypeface = probe.getTypeface();
		Paint paint = probe.getPaint();
		float maxNumber = 0;
		for (String sample : new String[]{"+999", "999K+", "999M+", "2B+", "−999", "−999K", "−999M",
				WatcherPresentation.format(Integer.MIN_VALUE), WatcherPresentation.format(Integer.MAX_VALUE), "0"}) {
			maxNumber = Math.max(maxNumber, paint.measureText(sample));
		}
		// At fractional densities, separately rounded drawing slots can exceed dp(32) by one pixel.
		int occupied = Math.max(dp(CHIP_PADDING + CHIP_ICON + CHIP_ICON_GAP + CHIP_PADDING),
				numberStart() + dp(CHIP_PADDING));
		chipWidth = Math.max(dp(CHIP_MIN_WIDTH), occupied
				+ (int) Math.ceil(maxNumber));
		Paint.FontMetrics metrics = paint.getFontMetrics();
		chipHeight = Math.max(dp(CHIP_MIN_HEIGHT), (int) Math.ceil(metrics.bottom - metrics.top) + dp(6));
		apply(probe, Role.THREAD);
		int textHeight = probe.getLineHeight();
		apply(probe, Role.TITLE);
		rowHeight = Math.max(dp(MIN_ROW_HEIGHT), Math.max(chipHeight + dp(4), Math.max(textHeight, probe.getLineHeight()) + dp(4)));
	}

	/** Legacy density floor grows for the selected text size, without resolving a new palette. */
	public static int minimumRowHeight(Context context) {
		int minimum = Math.round(MIN_ROW_HEIGHT * ResourceUtils.obtainDensity(context));
		if (Preferences.getDrawerTextScalePercent() == Preferences.DEFAULT_TEXT_SCALE) return minimum;
		TextView probe = new TextView(context);
		ThemeEngine.applyStyle(probe);
		ViewUtils.setTextSizeScaled(probe, 14); scaleText(probe, Preferences.getDrawerTextScalePercent());
		return Math.max(minimum, probe.getLineHeight() + Math.round(4 * ResourceUtils.obtainDensity(context)));
	}

	/** Call after resolving a fresh base size. Never scales LayoutParams or icon/touch dimensions. */
	public static void scaleText(TextView text, int percent) {
		float scale = Math.max(Preferences.MIN_TEXT_SCALE, Math.min(percent, Preferences.MAX_TEXT_SCALE)) / 100f;
		if (scale != 1f) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * scale);
	}

	public int dp(float value) { return Math.round(value * ResourceUtils.obtainDensity(context)); }
	public int numberStart() { return dp(CHIP_PADDING + CHIP_ICON + CHIP_ICON_GAP); }
	public float chipRadius() { return chipHeight / 2f; }

	public void apply(TextView text, Role role) {
		AppearanceTypography.apply(text, role == Role.TITLE ? AppearanceTypography.Role.DRAWER_COMPACT
				: AppearanceTypography.Role.DRAWER_BODY);
		scaleText(text, textScalePercent);
		text.setTextColor(role == Role.GROUP ? variant : onSurface);
	}
}
