package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One adapter row; only its trailing action wraps when accessible text cannot fit. */
public final class DrawerItemLayout extends LinearLayout {
	private final DrawerStyle style;
	private TextView text;
	private View action;
	private boolean thread;
	private boolean stacked;
	private int mainHeight;

	public DrawerItemLayout(Context context, DrawerStyle style) { super(context); this.style = style; }
	public void setContent(TextView text, View action) { this.text = text; this.action = action; }
	public void setThread(boolean thread) { this.thread = thread; }

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int width = MeasureSpec.getSize(widthSpec);
		int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
		int occupied = 0;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child == text || child.getVisibility() == GONE) continue;
			LayoutParams params = (LayoutParams) child.getLayoutParams();
			int childWidth = params.width >= 0 ? params.width : available;
			child.measure(MeasureSpec.makeMeasureSpec(childWidth,
					params.width >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
					MeasureSpec.makeMeasureSpec(params.height > 0 ? params.height : style.rowHeight,
							params.height > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
			occupied += child.getMeasuredWidth() + params.getMarginStart() + params.getMarginEnd();
		}
		int textWidth = available - occupied;
		stacked = action != null && action.getVisibility() != GONE
				&& textWidth - text.getPaddingLeft() - text.getPaddingRight() < style.dp(80);
		if (stacked) textWidth += action.getMeasuredWidth();
		boolean multiline = !thread || getResources().getConfiguration().fontScale > 1.15f
				|| textWidth < style.dp(140);
		if (text.getMaxLines() != (multiline ? 2 : 1)) {
			text.setSingleLine(!multiline); text.setMaxLines(multiline ? 2 : 1);
		}
		text.measure(MeasureSpec.makeMeasureSpec(Math.max(1, textWidth), MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
		mainHeight = Math.max(getMinimumHeight(), Math.max(style.rowHeight, text.getMeasuredHeight() + style.dp(4)));
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child == text || child.getVisibility() == GONE) continue;
			child.measure(MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(stacked && child == action ? style.rowHeight : mainHeight, MeasureSpec.EXACTLY));
		}
		setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(mainHeight
				+ (stacked ? action.getMeasuredHeight() : 0) + getPaddingTop() + getPaddingBottom(), heightSpec));
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int cursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) continue;
			LayoutParams params = (LayoutParams) child.getLayoutParams();
			int x, y;
			if (stacked && child == action) {
				x = rtl ? getPaddingLeft() : getWidth() - getPaddingRight() - child.getMeasuredWidth();
				y = getPaddingTop() + mainHeight;
			} else {
				cursor += rtl ? -params.getMarginStart() : params.getMarginStart();
				x = rtl ? cursor - child.getMeasuredWidth() : cursor;
				y = getPaddingTop() + (mainHeight - child.getMeasuredHeight()) / 2;
				cursor += (rtl ? -1 : 1) * (child.getMeasuredWidth() + params.getMarginEnd());
			}
			child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
		}
	}
}
