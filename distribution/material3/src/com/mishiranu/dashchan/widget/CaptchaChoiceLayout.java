package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Only arranges the manual symbol picker. All interactive views retain their owners and indices. */
final class CaptchaChoiceLayout extends LinearLayout {
	private final LinearLayout overview, keys;
	private final View heading, selected, task;
	private final ScrollView left, right;
	private final HorizontalScrollView overflow;
	private final FrameLayout gridFrame;
	private final int titleGap, selectionGap, taskGap, originalTaskHeight;
	private final float density;
	private boolean split;

	CaptchaChoiceLayout(Context context, LinearLayout overview, View heading, View selected, View task, LinearLayout keys) {
		super(context);
		this.overview = overview;
		this.heading = heading;
		this.selected = selected;
		this.task = task;
		this.keys = keys;
		density = getResources().getDisplayMetrics().density;
		titleGap = params(heading).bottomMargin;
		selectionGap = params(selected).bottomMargin;
		taskGap = task == null ? 0 : params(task).bottomMargin;
		originalTaskHeight = task == null ? 0 : params(task).height;
		setOrientation(HORIZONTAL);
		setBaselineAligned(false);
		left = new ScrollView(context);
		left.addView(overview, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		addView(left, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		right = new ScrollView(context);
		overflow = new HorizontalScrollView(context);
		overflow.setFillViewport(true);
		gridFrame = new FrameLayout(context);
		overflow.addView(gridFrame, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		right.addView(overflow, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		right.setVisibility(GONE);
		addView(right, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
	}

	private int dp(int n) { return Math.round(n * density); }
	private static LayoutParams params(View v) { return (LayoutParams) v.getLayoutParams(); }
	static boolean wide(int safeW, int safeH, int minWidth) {
		return safeW >= minWidth && safeW > safeH;
	}
	static int cellSize(int paneW, int paneH, int rows, int cols, int gap, int min, int max) {
		int byW = (paneW - gap * (cols - 1)) / cols;
		int byH = (paneH - gap * (rows - 1)) / rows;
		return Math.max(min, Math.min(max, Math.min(byW, byH)));
	}

	void split(boolean next) {
		if (next == split) return;
		split = next;
		((ViewGroup) keys.getParent()).removeView(keys);
		if (next) {
			gridFrame.addView(keys, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
		} else {
			overview.addView(keys, new LayoutParams(-1, -2));
		}
		params(heading).bottomMargin = next ? dp(6) : titleGap;
		params(selected).bottomMargin = next ? dp(6) : selectionGap;
		if (task != null) {
			params(task).bottomMargin = next ? 0 : taskGap;
			params(task).height = originalTaskHeight;
		}
		params(left).width = next ? 0 : -1;
		params(left).height = next ? -1 : -2;
		params(right).setMarginStart(next ? dp(16) : 0);
		right.setVisibility(next ? VISIBLE : GONE);
		left.scrollTo(0, 0);
		right.scrollTo(0, 0);
		overflow.scrollTo(0, 0);
		requestLayout();
	}

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		if (split) {
			int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
			int gap = dp(16), leftW = Math.max(1, (width - gap) / 2), rightW = Math.max(1, width - gap - leftW);
			params(left).width = leftW;
			if (task != null) {
				heading.measure(MeasureSpec.makeMeasureSpec(leftW, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
				int remaining = height - heading.getMeasuredHeight() - params(heading).bottomMargin
						- params(selected).height - params(selected).bottomMargin;
				// Keep the full image visible once scrolled into view, even in a very short pane.
				int taskHeight = Math.min(height, Math.max(dp(64), Math.min(originalTaskHeight, remaining)));
				if (params(task).height != taskHeight) {
					params(task).height = taskHeight;
					task.forceLayout();
					overview.forceLayout();
					left.forceLayout();
				}
			}
			int rows = keys.getChildCount();
			if (rows > 0 && keys.getChildAt(0) instanceof LinearLayout) {
				LinearLayout first = (LinearLayout) keys.getChildAt(0);
				int cols = first.getChildCount();
				if (cols > 0) {
					// Match the real row margins, including fractional screen densities.
					LayoutParams cell = params(first.getChildAt(0));
					int gx = cols > 1 ? cell.leftMargin + cell.rightMargin : 0;
					int gy = rows > 1 ? cell.topMargin + cell.bottomMargin : 0;
					int side = cellSize(rightW, height, rows, cols, Math.max(gx, gy), dp(48), dp(80));
					int gridWidth = cols * side + (cols - 1) * gx;
					FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) keys.getLayoutParams();
					if (p.width != gridWidth) {
						p.width = gridWidth;
						keys.forceLayout();
						gridFrame.forceLayout();
						overflow.forceLayout();
						right.forceLayout();
					}
				}
			}
		}
		super.onMeasure(widthSpec, heightSpec);
	}
}
