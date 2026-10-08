package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Overlay host outside FragmentContainerView. It never owns a page or a navigation gesture. */
public final class ThreadMotionLayout extends FrameLayout {
	private boolean motionBlocking;

	public ThreadMotionLayout(Context context, AttributeSet attrs) { super(context, attrs); }

	public void setMotionBlocking(boolean blocking) { motionBlocking = blocking; }

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		// The visible, transformed page must not have invisible full-screen touch targets.
		return motionBlocking || super.dispatchTouchEvent(event);
	}
}
