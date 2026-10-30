package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.media.PlayerButtonsLayout;
import java.util.List;

/** Stable children: resize only measures/layouts; it never reparents a pressed button. */
public final class PlayerButtonsGrid extends ViewGroup {
	private final int requestedColumns;
	private final int minimumCell;
	private int columns = 1;
	private int rows;
	private int cellWidth;

	public PlayerButtonsGrid(Context context, List<View> buttons, int columns, int minimumCellDp) {
		super(context);
		requestedColumns = columns;
		minimumCell = Math.round(minimumCellDp * getResources().getDisplayMetrics().density);
		for (View button : buttons) addView(button);
	}

	@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		cellWidth = minimumCell;
		int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child instanceof android.widget.TextView) {
				child.measure(natural, MeasureSpec.makeMeasureSpec(minimumCell, MeasureSpec.EXACTLY));
				cellWidth = Math.max(cellWidth, child.getMeasuredWidth());
			}
		}
		int count = getChildCount();
		int limit = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
				? cellWidth * Math.max(1, requestedColumns) : MeasureSpec.getSize(widthMeasureSpec);
		columns = PlayerButtonsLayout.effectiveColumns(requestedColumns, limit, cellWidth);
		rows = (count + columns - 1) / columns;
		for (int i = 0; i < count; i++) getChildAt(i).measure(
				MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(minimumCell, MeasureSpec.EXACTLY));
		setMeasuredDimension(resolveSize(Math.min(count, columns) * cellWidth, widthMeasureSpec),
				resolveSize(rows * minimumCell, heightMeasureSpec));
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		for (int i = 0; i < getChildCount(); i++) {
			int x = PlayerButtonsLayout.column(i, columns) * cellWidth;
			int y = (rows - 1 - PlayerButtonsLayout.rowFromBottom(i, columns)) * minimumCell;
			getChildAt(i).layout(x, y, x + cellWidth, y + minimumCell);
		}
	}

	@Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-2, -2); }
}
