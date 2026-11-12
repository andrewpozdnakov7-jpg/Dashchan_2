package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Decorative video marker and its first metadata token form one unbreakable text run. */
public final class VideoMetadataSpan extends ReplacementSpan {
	private final Drawable drawable;
	private final int gap;
	private final Rect capitals = new Rect();

	private VideoMetadataSpan(TextView view, CharSequence token) {
		drawable = view.getContext().getDrawable(R.drawable.ic_video_metadata_play).mutate();
		gap = Math.round(4f * ResourceUtils.obtainDensity(view));
		// The original token is the accessible text; no replacement-character or decorative glyph is inserted.
		setContentDescription(token.toString());
	}

	public static void bind(TextView view, CharSequence description, boolean individualVideo) {
		CharSequence original = description != null ? description : "";
		int start = firstTokenStart(original);
		if (individualVideo && start == original.length()) {
			original = view.getContext().getString(R.string.gallery_filter_video);
			start = 0;
		}
		if (!individualVideo && (!(original instanceof Spanned)
				|| ((Spanned) original).getSpans(0, original.length(), VideoMetadataSpan.class).length == 0)) {
			view.setText(original);
			return;
		}
		SpannableString text = new SpannableString(original);
		// Usually the caller supplies raw model text; stripping our own spans also makes rebind idempotent.
		for (VideoMetadataSpan previous : text.getSpans(0, text.length(), VideoMetadataSpan.class)) {
			text.removeSpan(previous);
		}
		if (individualVideo && start < text.length()) {
			int end = start;
			while (end < text.length() && !isSpace(text.charAt(end))) end++;
			text.setSpan(new VideoMetadataSpan(view, text.subSequence(start, end)), start, end,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		view.setText(text);
	}

	private static boolean isSpace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

	private static int firstTokenStart(CharSequence text) {
		int start = 0;
		while (start < text.length() && isSpace(text.charAt(start))) start++;
		return start;
	}

	private static boolean isRtl(CharSequence text, int start, int end) {
		// MP4/WEBM stay LTR even in an RTL paragraph; an RTL provider label keeps its own direction.
		return TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, start, end - start);
	}

	private static float tokenWidth(Paint paint, CharSequence text, int start, int end, boolean rtl) {
		return paint.getRunAdvance(text, start, end, start, end, rtl, end);
	}

	@Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
		// Populate the existing metrics even if this span covers the entire caption; never enlarge the line.
		if (fm != null) paint.getFontMetricsInt(fm);
		int size = Math.round(paint.getTextSize());
		return (int) Math.ceil(size + gap + tokenWidth(paint, text, start, end, isRtl(text, start, end)));
	}

	@Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
			int top, int y, int bottom, Paint paint) {
		boolean rtl = isRtl(text, start, end);
		float tokenWidth = tokenWidth(paint, text, start, end, rtl);
		int size = Math.round(paint.getTextSize());
		float iconX = rtl ? x + tokenWidth + gap : x;
		float textX = rtl ? x : x + size + gap;
		paint.getTextBounds("H", 0, 1, capitals);
		float center = y + (capitals.top + capitals.bottom) * .5f;
		int save = canvas.save();
		try {
			canvas.translate(iconX, center - size * .5f);
			drawable.setBounds(0, 0, size, size);
			drawable.setTint(paint.getColor());
			drawable.draw(canvas);
		} finally { canvas.restoreToCount(save); }
		Paint.Align alignment = paint.getTextAlign();
		try {
			paint.setTextAlign(Paint.Align.LEFT);
			canvas.drawTextRun(text, start, end, start, end, textX, y, rtl, paint);
		} finally { paint.setTextAlign(alignment); }
	}
}
