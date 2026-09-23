package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Shared palette for actual popups and their settings preview. */
public final class PopupColors {
	public final int background;
	public final int foreground;

	private PopupColors(int background, int foreground) {
		this.background = background;
		this.foreground = foreground;
	}

	public static PopupColors obtain(Context context) {
		switch (Preferences.getPopupColorMode()) {
			case CUSTOM:
				return new PopupColors(Preferences.getPopupBackground(), Preferences.getPopupForeground());
			case TOOLBAR:
				Context toolbarContext = new ContextThemeWrapper(context,
						ResourceUtils.getResourceId(context, android.R.attr.actionBarTheme, 0));
				return new PopupColors(ThemeEngine.getTheme(context).primary | 0xff000000,
						ResourceUtils.getColor(toolbarContext, android.R.attr.textColorPrimary));
			case THEME:
				boolean dark = ThemeEngine.getTheme(context).base == ThemeEngine.Theme.Base.DARK;
				return new PopupColors(context.getColor(dark ? R.color.background_popup_dark
						: R.color.background_popup_light), dark ? Color.WHITE : Color.BLACK);
			default:
				return new PopupColors(ResourceUtils.getColor(context, R.attr.colorClickableToastBackground),
						ResourceUtils.getColor(context, R.attr.colorTextClickableToast));
		}
	}
}
