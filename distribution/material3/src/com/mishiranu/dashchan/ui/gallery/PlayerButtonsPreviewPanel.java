package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.List;

/** Wraps only the buttons, never the weighted space reserved for dropping them. */
public final class PlayerButtonsPreviewPanel extends FrameLayout {
	public PlayerButtonsPreviewPanel(Context context, List<View> buttons, int columns) {
		super(context);
		float density = getResources().getDisplayMetrics().density;
		GradientDrawable background = new GradientDrawable();
		background.setColor(0x40000000);
		background.setCornerRadius(16 * density);
		background.setStroke(Math.max(1, Math.round(density)), 0x26ffffff);
		setBackground(background);
		int padding = Math.round(4 * density);
		setPadding(padding, padding, padding, padding);
		// Keep an empty destination visible and easy to drop onto.
		if (buttons.isEmpty()) {
			setMinimumWidth(Math.round(56 * density));
			setMinimumHeight(Math.round(56 * density));
		}
		addView(new PlayerButtonsGrid(context, buttons, columns, 48),
				new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT));
	}
}
