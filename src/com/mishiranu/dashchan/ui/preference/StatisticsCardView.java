package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;

/** Read-only statistics, with no nested scrolling or navigation gesture ownership. */
final class StatisticsCardView extends LinearLayout {
	final TextView title;
	final TextView period;
	final MetricsLayout metrics;

	StatisticsCardView(Context context) {
		super(context);
		setOrientation(VERTICAL);
		RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
		params.setMargins(dp(8), dp(8), dp(8), dp(8));
		setLayoutParams(params);
		title = text(context, android.R.style.TextAppearance_Medium);
		title.setPaddingRelative(dp(16), dp(12), dp(16), 0);
		title.setAccessibilityHeading(true);
		addView(title, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		period = text(context, android.R.style.TextAppearance_Small);
		period.setPaddingRelative(dp(16), dp(4), dp(16), dp(12));
		addView(period, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		metrics = new MetricsLayout(context);
		metrics.setPaddingRelative(dp(16), dp(12), dp(16), dp(16));
		addView(metrics, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		// Reuse the app's existing card surface, shape, title, caption and refresh policy.
		InterfaceAppearance.standardCatalogCard(this, title, period);
	}

	void bind(String heading, String[] values, String repliesPeriod) {
		if (values.length != metrics.values.length) throw new IllegalArgumentException("Four statistics required");
		title.setText(heading);
		period.setText(repliesPeriod);
		period.setVisibility(repliesPeriod != null ? View.VISIBLE : View.GONE);
		for (int i = 0; i < values.length; i++) metrics.values[i].setText(values[i]);
	}

	private int dp(int value) { return Math.round(value * ResourceUtils.obtainDensity(this)); }
	private static TextView text(Context context, int appearance) {
		TextView text = new TextView(context);
		TextViewCompat.setTextAppearance(text, appearance);
		ThemeEngine.applyStyle(text);
		// Establish the real line policy BEFORE the appearance helper snapshots it.
		text.setSingleLine(false);
		text.setMaxLines(Integer.MAX_VALUE);
		text.setEllipsize(null);
		text.setGravity(Gravity.START);
		text.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
		return text;
	}

	static final class MetricsLayout extends ViewGroup {
		final TextView[] values = new TextView[4];
		final TextView[] labels = new TextView[4];
		private final int[] rowHeights = new int[4];
		private int columns = 1;
		private int cellWidth;

		MetricsLayout(Context context) {
			super(context);
			int[] titles = {R.string.views, R.string.posts, R.string.threads, R.string.statistics_replies_received};
			for (int i = 0; i < titles.length; i++) {
				LinearLayout cell = new LinearLayout(context);
				cell.setOrientation(LinearLayout.VERTICAL);
				cell.setScreenReaderFocusable(true);
				values[i] = text(context, android.R.style.TextAppearance_Large);
				labels[i] = text(context, android.R.style.TextAppearance_Small);
				labels[i].setText(titles[i]);
				cell.addView(values[i], new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
				LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
				labelParams.topMargin = dp(4);
				cell.addView(labels[i], labelParams);
				addView(cell, new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
				InterfaceAppearance.standardStatisticValue(values[i]);
				InterfaceAppearance.standardCustomCaption(labels[i]);
			}
		}

		private int dp(int value) { return Math.round(value * ResourceUtils.obtainDensity(this)); }
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? dp(280) : MeasureSpec.getSize(widthSpec);
			int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
			int gap = dp(16);
			float scale = Math.max(1f, getResources().getConfiguration().fontScale);
			int minimum = Math.round(dp(120) * scale);
			// Large counters also need a full-width cell; never shrink or ellipsize their digits.
			for (TextView value : values) minimum = Math.max(minimum,
					(int) Math.ceil(Layout.getDesiredWidth(value.getText(), value.getPaint()))
							+ value.getCompoundPaddingLeft() + value.getCompoundPaddingRight());
			columns = available >= 2L * minimum + gap ? 2 : 1;
			cellWidth = Math.max(0, (available - (columns - 1) * gap) / columns);
			java.util.Arrays.fill(rowHeights, 0);
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
				rowHeights[i / columns] = Math.max(rowHeights[i / columns], child.getMeasuredHeight());
			}
			int rows = (getChildCount() + columns - 1) / columns;
			int height = getPaddingTop() + getPaddingBottom() + Math.max(0, rows - 1) * dp(12);
			for (int row = 0; row < rows; row++) height += rowHeights[row];
			setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
		}

		@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
			boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			int y = getPaddingTop();
			for (int i = 0; i < getChildCount(); i++) {
				int column = i % columns;
				if (i > 0 && column == 0) y += rowHeights[i / columns - 1] + dp(12);
				int x = rtl ? getWidth() - getPaddingRight() - cellWidth - column * (cellWidth + dp(16))
						: getPaddingLeft() + column * (cellWidth + dp(16));
				View child = getChildAt(i);
				child.layout(x, y, x + cellWidth, y + child.getMeasuredHeight());
			}
		}
	}
}
