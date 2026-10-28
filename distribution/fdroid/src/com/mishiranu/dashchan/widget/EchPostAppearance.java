package com.mishiranu.dashchan.widget;

import android.widget.TextView;

/** F-Droid retains the legacy post presentation; no Material dependencies. */
public final class EchPostAppearance {
	private EchPostAppearance() {}
	public static void section(TextView view) {}
	public static void action(TextView view, boolean poll) {
		view.setTypeface(android.graphics.Typeface.create(view.getTypeface(), view.isSelected()
				? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
	}
}
