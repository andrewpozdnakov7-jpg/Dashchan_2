package com.mishiranu.dashchan.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;
import com.mishiranu.dashchan.R;

/** Informational editor chrome, outside the editable/scrolling text and its draft state. */
@SuppressLint("AppCompatCustomView")
public final class PostingAttachmentSummaryView extends TextView {
	private String fileCount = "", fileSize;
	public PostingAttachmentSummaryView(Context context) { this(context, null); }
	public PostingAttachmentSummaryView(Context context, AttributeSet attrs) {
		super(context, attrs);
		setClickable(false); setFocusable(false); setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
		setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
	}
	public void setSummary(String count, String size) {
		if (TextUtils.equals(fileCount, count) && TextUtils.equals(fileSize, size)) return;
		fileCount = count; fileSize = size;
		updateText(getWidth() > 0 ? getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight() : Integer.MAX_VALUE);
	}
	private void updateText(int available) {
		String text = fileSize != null ? getContext().getString(R.string.posting_attachment_summary, fileCount, fileSize) : fileCount;
		if (fileSize != null && Layout.getDesiredWidth(text, getPaint()) > Math.max(0, available)) {
			// A wrapped pair has no stranded separator. Preserve both groups and the user's font scale.
			text = fileCount + "\n" + fileSize;
		}
		if (!TextUtils.equals(getText(), text)) setText(text);
	}
	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
				: Math.max(0, MeasureSpec.getSize(widthSpec) - getCompoundPaddingLeft() - getCompoundPaddingRight());
		updateText(available);
		super.onMeasure(widthSpec, heightSpec);
	}
}
