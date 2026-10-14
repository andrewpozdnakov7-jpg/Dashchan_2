package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.text.Layout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Fixed single-line row. Fit only label typography, never the attachment or checkbox glyphs. */
final class PostingUtilityRow extends LinearLayout {
	private static final class Font {
		float base, applied;
		Font(float size) { base = applied = size; }
	}
	private final IdentityHashMap<CompoundButton, Font> fonts = new IdentityHashMap<>();
	private final TextPaint measuring = new TextPaint();

	PostingUtilityRow(Context context) { super(context); setOrientation(HORIZONTAL); }

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		List<CompoundButton> options = new ArrayList<>();
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child instanceof CompoundButton) {
				CompoundButton option = (CompoundButton) child;
				Font font = fonts.get(option);
				float size = option.getTextSize();
				if (font == null) fonts.put(option, new Font(size));
				else if (Math.abs(size - font.applied) > .01f) font.base = size; // A genuine theme/configuration update.
				if (child.getVisibility() != GONE) options.add(option);
			}
		}
		fonts.keySet().removeIf(option -> option.getParent() != this);
		int gap = Math.round(4 * getResources().getDisplayMetrics().density);
		for (int i = 0; i < options.size(); i++) {
			((LayoutParams) options.get(i).getLayoutParams()).setMarginEnd(i + 1 < options.size() ? gap : 0);
		}
		int fixed = getPaddingLeft() + getPaddingRight();
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) continue;
			LayoutParams params = (LayoutParams) child.getLayoutParams();
			fixed += params.getMarginStart() + params.getMarginEnd();
			fixed += child instanceof CompoundButton
					? ((CompoundButton) child).getCompoundPaddingLeft() + ((CompoundButton) child).getCompoundPaddingRight()
					: Math.max(child.getMinimumWidth(), Math.max(0, params.width));
		}
		int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
				? fixed + measuredText(options, 1f) : MeasureSpec.getSize(widthSpec);
		int budget = Math.max(0, available - fixed);
		float scale = 1f;
		if (measuredText(options, scale) > budget) {
			float low = 0f, high = 1f;
			for (int i = 0; i < 18; i++) {
				float candidate = (low + high) / 2f;
				if (measuredText(options, candidate) <= budget) low = candidate;
				else high = candidate;
			}
			scale = low;
		}
		for (CompoundButton option : options) {
			Font font = fonts.get(option);
			float size = font.base * scale;
			if (Math.abs(option.getTextSize() - size) > .001f) option.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
			font.applied = option.getTextSize();
			LayoutParams params = (LayoutParams) option.getLayoutParams();
			params.width = option.getCompoundPaddingLeft() + option.getCompoundPaddingRight()
					+ (int) Math.ceil(Layout.getDesiredWidth(option.getText(), option.getPaint()));
			params.height = LayoutParams.WRAP_CONTENT; params.weight = 0f;
		}
		super.onMeasure(widthSpec, heightSpec);
	}

	private int measuredText(List<CompoundButton> options, float scale) {
		int width = 0;
		for (CompoundButton option : options) {
			measuring.set(option.getPaint());
			measuring.setTextSize(fonts.get(option).base * scale);
			width += (int) Math.ceil(Layout.getDesiredWidth(option.getText(), measuring));
		}
		return width;
	}
}
