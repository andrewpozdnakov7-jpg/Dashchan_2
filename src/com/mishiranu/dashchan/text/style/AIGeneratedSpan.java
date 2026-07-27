package com.mishiranu.dashchan.text.style;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import com.mishiranu.dashchan.graphics.ColorScheme;

/**
 * Semantic presentation span for the generated part of an AI post.
 *
 * The span stays inert unless the corresponding display preference is enabled. When active, it combines the
 * regular spoiler interaction with the underlying spoiler background and a paragraph indent.
 */
public class AIGeneratedSpan extends SpoilerSpan implements LeadingMarginSpan {
	private int backgroundColor;
	private int leadingMargin;
	private boolean displayEnabled;
	private boolean spoilersEnabled;

	@Override
	public void applyColorScheme(ColorScheme colorScheme) {
		super.applyColorScheme(colorScheme);
		if (colorScheme != null) {
			backgroundColor = colorScheme.spoilerBackgroundColor;
		}
	}

	public void setDisplayEnabled(boolean enabled, int leadingMargin) {
		displayEnabled = enabled;
		this.leadingMargin = enabled ? leadingMargin : 0;
		super.setEnabled(enabled && spoilersEnabled);
	}

	@Override
	public void setEnabled(boolean enabled) {
		spoilersEnabled = enabled;
		super.setEnabled(displayEnabled && enabled);
	}

	@Override
	public void updateDrawState(TextPaint paint) {
		if (displayEnabled) {
			paint.bgColor = backgroundColor;
		}
		super.updateDrawState(paint);
	}

	@Override
	public int getLeadingMargin(boolean first) {
		return leadingMargin;
	}

	@Override
	public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom,
			CharSequence text, int start, int end, boolean first, Layout layout) {}
}
