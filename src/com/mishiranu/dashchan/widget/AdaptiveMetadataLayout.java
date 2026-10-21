package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Opt-in service metadata only: move the trailing date/count below instead of overlapping. */
final class AdaptiveMetadataLayout extends LinearLayout {
	AdaptiveMetadataLayout(Context context) { super(context); }
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		if (getChildCount() == 2) {
			TextView first = (TextView) getChildAt(0), end = (TextView) getChildAt(1);
			LayoutParams a = (LayoutParams) first.getLayoutParams(), b = (LayoutParams) end.getLayoutParams();
			int available = MeasureSpec.getSize(widthSpec) - getPaddingStart() - getPaddingEnd();
			boolean stacked = InterfaceAppearance.isEnabled(getContext()) && first.getVisibility() != View.GONE
					&& end.getVisibility() != View.GONE && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
					&& Layout.getDesiredWidth(first.getText(), first.getPaint()) + Layout.getDesiredWidth(end.getText(), end.getPaint())
					+ Math.round(8 * getResources().getDisplayMetrics().density) > available;
			setOrientation(stacked ? VERTICAL : HORIZONTAL);
			a.width = stacked ? LayoutParams.MATCH_PARENT : 0; a.weight = stacked ? 0 : 1;
			b.setMarginStart(stacked ? 0 : Math.round(8 * getResources().getDisplayMetrics().density));
		}
		super.onMeasure(widthSpec, heightSpec);
	}
}
