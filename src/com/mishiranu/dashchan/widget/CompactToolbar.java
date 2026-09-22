package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toolbar;

/** Keeps navigation compact without shrinking its touch target below 48 dp. */
public class CompactToolbar extends Toolbar {
	public CompactToolbar(Context context, AttributeSet attrs) {
		super(context, attrs);
		ThemeEngine.applyToolbarStyle(this);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		Drawable navigationIcon = getNavigationIcon();
		if (navigationIcon != null) {
			int width = Math.round(48f * getResources().getDisplayMetrics().density);
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				// Only the actual navigation button: leave menu, search and custom views alone.
				if (child instanceof ImageButton && ((ImageButton) child).getDrawable() == navigationIcon) {
					if (child.getMinimumWidth() != width) {
						child.setMinimumWidth(width);
					}
					ViewGroup.LayoutParams params = child.getLayoutParams();
					params.width = width;
					break;
				}
			}
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}
}
