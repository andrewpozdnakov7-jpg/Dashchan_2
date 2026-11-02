package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Measures the original attachment children in source order; never rebuilds or reindexes them. */
public final class AttachmentTilesLayout extends ViewGroup {
    private int columns, tileWidth, gap;
    private int[] rowHeights = new int[0];
    public AttachmentTilesLayout(Context context) { super(context); setMotionEventSplittingEnabled(false); }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-2, -2); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        gap = StandardAppearance.dp(this, AppearanceShapes.SPACE_8);
        int max = StandardAppearance.dp(this, AppearanceShapes.STANDARD_DIALOG_MAX_WIDTH);
        int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? max : MeasureSpec.getSize(widthSpec);
        int width = Math.min(max, Math.max(1, available));
        int min = Math.round(StandardAppearance.dp(this, 120) * Math.max(1f, getResources().getConfiguration().fontScale));
        columns = Math.max(1, Math.min(3, (width - gap) / Math.max(1, min + gap)));
        tileWidth = Math.max(1, (width - gap * (columns + 1)) / columns);
        rowHeights = new int[(getChildCount() + columns - 1) / columns];
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowHeights[i / columns] = Math.max(rowHeights[i / columns], child.getMeasuredHeight());
        }
        int height = gap;
        for (int row : rowHeights) height += row + gap;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int y = gap;
        for (int row = 0; row < rowHeights.length; row++) {
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                if (index >= getChildCount()) break;
                View child = getChildAt(index);
                int origin = Math.max(0, (getWidth() - columns * tileWidth - (columns + 1) * gap) / 2);
                int x = origin + gap + col * (tileWidth + gap);
                if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = getWidth() - x - tileWidth;
                child.layout(x, y, x + tileWidth, y + child.getMeasuredHeight());
            }
            y += rowHeights[row] + gap;
        }
    }
}
