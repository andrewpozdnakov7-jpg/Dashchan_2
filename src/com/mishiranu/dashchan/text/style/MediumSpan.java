package com.mishiranu.dashchan.text.style;

import android.graphics.Typeface;
import android.text.style.StyleSpan;

/**
 * Historical name retained because archive export recognizes this span as bold markup.
 */
public class MediumSpan extends StyleSpan {
	public MediumSpan() {
		super(Typeface.BOLD);
	}
}
