package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;

/** Server-provided choice instructions must remain readable at any length or font scale. */
public final class ChoiceDialogInstruction {
	private ChoiceDialogInstruction() {}

	public static void allowFullText(TextView view) {
		view.setSingleLine(false);
		view.setMaxLines(Integer.MAX_VALUE);
		view.setEllipsize(null);
		view.setHorizontallyScrolling(false);
	}

	public static TextView create(Context context, CharSequence text, boolean compact) {
		TextView view = new TextView(context);
		view.setText(text);
		view.setTextColor(ResourceUtils.getColor(context, android.R.attr.textColorPrimary));
		view.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
		ViewUtils.setTextSizeScaled(view, compact ? 16 : 20);
		allowFullText(view);
		return view;
	}

	/** A separate viewport leaves the choice list scrollable without shifting answer indices. */
	public static ScrollView createScrollableTitle(Context context, CharSequence text) {
		ScrollView scroll = new ScrollView(context) {
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				int limit = Math.max(1, ViewUtils.getWindowContentSize(getContext()).y / 3);
				if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
					limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
				}
				super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
			}
		};
		TextView title = create(context, text, false);
		title.setId(R.id.standard_dialog_title);
		int inset = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_text);
		title.setPaddingRelative(inset, inset, inset, Math.round(16 * ResourceUtils.obtainDensity(context)));
		InterfaceAppearance.standardDialogTitle(title);
		allowFullText(title);
		scroll.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		return scroll;
	}
}
