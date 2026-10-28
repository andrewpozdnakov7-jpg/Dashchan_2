package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Rect;
import android.widget.ScrollView;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

/** Opted-in form body only; native dialog title/actions remain outside this viewport. */
public final class DialogFormScrollView extends ScrollView {
	private final Rect visible = new Rect();
	public DialogFormScrollView(Context context) { super(context); }
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		getWindowVisibleDisplayFrame(visible);
		int available = visible.height() > 0 ? visible.height() : ViewUtils.getWindowContentSize(getContext()).y;
		int limit = Math.min(available, Math.round(AppearanceShapes.DIALOG_FORM_MAX_HEIGHT * ResourceUtils.obtainDensity(this)));
		if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
		super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.max(0, limit), MeasureSpec.AT_MOST));
	}
}
