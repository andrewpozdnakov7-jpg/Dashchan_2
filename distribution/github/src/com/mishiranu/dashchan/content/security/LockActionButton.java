package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import androidx.appcompat.widget.AppCompatButton;

/** One accessible button, with its icon + wrapping text centered as a single group. */
final class LockActionButton extends AppCompatButton {
	LockActionButton(Context context) { super(context); setFilterTouchesWhenObscured(true); }
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		super.onMeasure(widthSpec, heightSpec);
		Drawable icon = getCompoundDrawablesRelative()[0];
		Layout text = getLayout();
		if (icon == null || text == null) return;
		float textWidth = 0;
		for (int i = 0; i < text.getLineCount(); i++) textWidth = Math.max(textWidth, text.getLineWidth(i));
		int group = (int) Math.ceil(textWidth) + icon.getBounds().width() + getCompoundDrawablePadding();
		int minimum = Math.round(16 * getResources().getDisplayMetrics().density);
		int padding = Math.max(minimum, (getMeasuredWidth() - group) / 2);
		if (padding != getPaddingStart() || padding != getPaddingEnd()) {
			setPaddingRelative(padding, getPaddingTop(), padding, getPaddingBottom());
			super.onMeasure(widthSpec, heightSpec);
		}
	}
}
