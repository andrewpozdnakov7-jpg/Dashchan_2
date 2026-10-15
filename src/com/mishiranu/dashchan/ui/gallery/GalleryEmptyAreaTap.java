package com.mishiranu.dashchan.ui.gallery;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.recyclerview.widget.RecyclerView;
import java.util.function.BooleanSupplier;

/** Passive empty-grid tap recognition; input continues through the original RecyclerView dispatch. */
final class GalleryEmptyAreaTap {
	private final RecyclerView recyclerView;
	private final BooleanSupplier enabled;
	private final Runnable close;
	private final GestureDetector detector;
	private boolean candidate;

	GalleryEmptyAreaTap(RecyclerView recyclerView, BooleanSupplier enabled, Runnable close) {
		this.recyclerView = recyclerView; this.enabled = enabled; this.close = close;
		detector = new GestureDetector(recyclerView.getContext(), new GestureDetector.SimpleOnGestureListener() {
			@Override public boolean onDown(MotionEvent event) {
				candidate = available() && empty(event);
				return candidate;
			}
			@Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
				candidate = false; return false;
			}
			@Override public void onLongPress(MotionEvent event) { candidate = false; }
			@Override public boolean onSingleTapUp(MotionEvent event) {
				boolean accepted = candidate && available() && empty(event)
						&& event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout();
				candidate = false;
				if (accepted) close.run();
				return accepted;
			}
		});
	}

	private boolean available() {
		return enabled.getAsBoolean() && recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
				&& !recyclerView.isComputingLayout() && !recyclerView.hasPendingAdapterUpdates();
	}

	private boolean empty(MotionEvent event) {
		float x = event.getX(), y = event.getY();
		if (x < 0 || y < 0 || x >= recyclerView.getWidth() || y >= recyclerView.getHeight()) return false;
		// The native scrollbar is a control, not blank space.
		int bar = recyclerView.getVerticalScrollbarWidth();
		if ((recyclerView.canScrollVertically(-1) || recyclerView.canScrollVertically(1))
				&& (x < bar || x >= recyclerView.getWidth() - bar)) return false;
		return recyclerView.findChildViewUnder(x, y) == null;
	}

	void onTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) candidate = false;
		detector.onTouchEvent(event);
		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) candidate = false;
	}
}
