package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import com.mishiranu.dashchan.R;

/** Blocks only transient presentation ownership, without altering formatting-button enabled state. */
public final class MotionToolbarExtra extends FrameLayout {
	public MotionToolbarExtra(Context context, AttributeSet attrs) { super(context, attrs); }
	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		return getTag(R.id.screen_motion_owner) != null || super.dispatchTouchEvent(event);
	}
}
