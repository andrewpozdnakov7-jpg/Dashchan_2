package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

/** One inline count, without another click/focus target or whitespace-based layout. */
public final class DrawerCountSpan extends ReplacementSpan {
	private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final String count;
	private final int gap;
	private final boolean rtl;

	private DrawerCountSpan(int count, DrawerStyle style, boolean rtl) {
		this.count = Integer.toString(count); this.rtl = rtl;
		gap = style.dp(DrawerStyle.COUNT_GAP);
		countPaint.setTypeface(style.counterTypeface); countPaint.setTextSize(style.counterTextSize);
		countPaint.setFontFeatureSettings("tnum"); countPaint.setColor(style.variant);
	}

	public static CharSequence format(CharSequence title, int count, DrawerStyle style, boolean rtl) {
		// The single semantic space is replaced by an exact measured 6dp gap.
		SpannableString text = new SpannableString(title + " " + count);
		text.setSpan(new DrawerCountSpan(count, style, rtl), title.length(), text.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return text;
	}

	@Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
		if (fm != null) {
			paint.getFontMetricsInt(fm);
			Paint.FontMetricsInt metrics = countPaint.getFontMetricsInt();
			fm.top = Math.min(fm.top, metrics.top); fm.ascent = Math.min(fm.ascent, metrics.ascent);
			fm.bottom = Math.max(fm.bottom, metrics.bottom); fm.descent = Math.max(fm.descent, metrics.descent);
		}
		return gap + (int) Math.ceil(countPaint.measureText(count));
	}

	@Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
			int top, int y, int bottom, Paint paint) {
		canvas.drawText(count, x + (rtl ? 0 : gap), y, countPaint);
	}
}
