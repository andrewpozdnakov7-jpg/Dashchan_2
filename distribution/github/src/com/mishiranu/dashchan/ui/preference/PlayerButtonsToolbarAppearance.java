package com.mishiranu.dashchan.ui.preference;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.IdentityHashMap;
import java.util.Map;

/** Reversible, page-local paint only. Does not replace toolbar children or navigation handlers. */
final class PlayerButtonsToolbarAppearance {
	private final ViewGroup toolbar;
	private final View surround;
	private final Drawable toolbarBackground, surroundBackground;
	private final float toolbarElevation, surroundElevation;
	private final Map<TextView, ColorStateList> textColors = new IdentityHashMap<>();
	private final Map<ImageView, ColorStateList> imageTints = new IdentityHashMap<>();
	private final View.OnLayoutChangeListener layoutListener;
	PlayerButtonsToolbarAppearance(ViewGroup toolbar, PlayerButtonsColors colors) {
		this.toolbar = toolbar;
		surround = toolbar.getParent() instanceof View ? (View) toolbar.getParent() : toolbar;
		toolbarBackground = toolbar.getBackground(); surroundBackground = surround.getBackground();
		toolbarElevation = toolbar.getElevation(); surroundElevation = surround.getElevation();
		// setBackgroundColor mutates an existing ColorDrawable, including the saved original.
		// Install independent temporary drawables so restore() can return the untouched theme.
		toolbar.setBackground(new ColorDrawable(colors.background));
		surround.setBackground(new ColorDrawable(colors.background));
		toolbar.setElevation(0); surround.setElevation(0);
		tint(toolbar, colors.ink);
		layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> tint(toolbar, colors.ink);
		toolbar.addOnLayoutChangeListener(layoutListener);
	}
	private void tint(View view, int color) {
		if (view instanceof TextView) {
			TextView text = (TextView) view;
			if (!textColors.containsKey(text)) textColors.put(text, text.getTextColors());
			text.setTextColor(color);
		} else if (view instanceof ImageView) {
			ImageView image = (ImageView) view;
			if (!imageTints.containsKey(image)) imageTints.put(image, image.getImageTintList());
			image.setImageTintList(ColorStateList.valueOf(color));
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) tint(group.getChildAt(i), color);
		}
	}
	void restore() {
		toolbar.removeOnLayoutChangeListener(layoutListener);
		toolbar.setBackground(toolbarBackground); surround.setBackground(surroundBackground);
		toolbar.setElevation(toolbarElevation); surround.setElevation(surroundElevation);
		for (Map.Entry<TextView, ColorStateList> entry : textColors.entrySet()) entry.getKey().setTextColor(entry.getValue());
		for (Map.Entry<ImageView, ColorStateList> entry : imageTints.entrySet()) entry.getKey().setImageTintList(entry.getValue());
	}
}
