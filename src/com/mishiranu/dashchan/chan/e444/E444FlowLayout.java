package com.mishiranu.dashchan.chan.e444;

import android.content.Context;
import android.view.View;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.LinebreakLayout;

/** Local wrapping for ECH actions; unrelated post/button layouts keep their existing geometry. */
final class E444FlowLayout extends LinebreakLayout {
	E444FlowLayout(Context context) { super(context); }
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		if (!InterfaceAppearance.isEnabled(getContext())) { super.onMeasure(widthSpec, heightSpec); return; }
		boolean unlimited = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
		int limit = unlimited ? Integer.MAX_VALUE : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
		int lineWidth = 0, lineHeight = 0, width = 0, height = 0;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
			child.measure(MeasureSpec.makeMeasureSpec(unlimited ? 0 : limit, unlimited ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST),
					MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
			int gap = lineWidth > 0 ? getHorizontalSpacing() : 0;
			if (lineWidth > 0 && lineWidth + gap + child.getMeasuredWidth() > limit) {
				width = Math.max(width, lineWidth); height += lineHeight + getVerticalSpacing();
				lineWidth = 0; lineHeight = 0; gap = 0;
			}
			lineWidth += gap + child.getMeasuredWidth(); lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
		}
		setMeasuredDimension(resolveSize(Math.max(width, lineWidth) + getPaddingLeft() + getPaddingRight(), widthSpec),
				resolveSize(height + lineHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
	}
	@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
		if (!InterfaceAppearance.isEnabled(getContext())) { super.onLayout(changed, l, t, r, b); return; }
		int available = getWidth() - getPaddingLeft() - getPaddingRight();
		int x = 0, y = getPaddingTop(), lineHeight = 0;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
			int gap = x > 0 ? getHorizontalSpacing() : 0, width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
			if (x > 0 && x + gap + width > available) { x = 0; y += lineHeight + getVerticalSpacing(); lineHeight = 0; gap = 0; }
			x += gap;
			int left = rtl ? getWidth() - getPaddingRight() - x - width : getPaddingLeft() + x;
			child.layout(left, y, left + width, y + height); x += width; lineHeight = Math.max(lineHeight, height);
		}
	}
}
