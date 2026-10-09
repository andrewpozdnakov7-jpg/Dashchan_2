package com.mishiranu.dashchan.widget;

import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Settings-driven drawer surface, independent of motion and distribution. */
final class DrawerAppearance {
	private final View panel;
	private final Drawable originalBackground, roundedBackground;
	private final ViewOutlineProvider originalOutline;
	private final boolean originalClip;
	private float radius;
	private final ViewOutlineProvider roundedOutline = new ViewOutlineProvider() {
		@Override public void getOutline(View view, Outline outline) {
			boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			int extension = (int) Math.ceil(radius);
			// Round only the edge facing the content, not the attached screen edge.
			outline.setRoundRect(rtl ? 0 : -extension, 0,
					view.getWidth() + (rtl ? extension : 0), view.getHeight(), radius);
		}
	};

	DrawerAppearance(View panel) {
		this.panel = panel;
		originalBackground = panel.getBackground();
		originalOutline = panel.getOutlineProvider();
		originalClip = panel.getClipToOutline();
		Drawable.ConstantState state = originalBackground != null ? originalBackground.getConstantState() : null;
		Drawable surface = state != null ? state.newDrawable(panel.getResources()).mutate() : originalBackground;
		// Clipped corners expose the real content. Prevent DrawerLayout's opaque-
		// rectangle optimization from leaving a blank base behind those corners.
		roundedBackground = surface != null ? new InsetDrawable(surface, 0) {
			@SuppressWarnings("deprecation") // Drawable opacity contract used by DrawerLayout.
			@Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
		} : null;
	}

	void update() {
		radius = Preferences.isRoundedDialogs()
				? Preferences.getRoundedDialogsRadius() * ResourceUtils.obtainDensity(panel) : 0f;
		boolean rounded = radius > 0f;
		panel.setOutlineProvider(rounded ? roundedOutline : originalOutline);
		panel.setClipToOutline(rounded || originalClip);
		panel.setBackground(rounded ? roundedBackground : originalBackground);
		panel.invalidateOutline();
	}
}
