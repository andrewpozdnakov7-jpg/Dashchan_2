package com.mishiranu.dashchan.widget;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.IdentityHashMap;

public class RecyclerScrollTracker {
	private final OnScrollListener listener;
	private final IdentityHashMap<RecyclerView, Registration> registrations = new IdentityHashMap<>();

	public RecyclerScrollTracker(OnScrollListener listener) { this.listener = listener; }

	public void attach(RecyclerView view) { attach(view, false); }

	public void attach(RecyclerView view, boolean expandedThread) {
		detach(view);
		Registration registration = expandedThread ? new ThreadRegistration(view) : new LegacyRegistration(view);
		registrations.put(view, registration);
		registration.attach();
	}

	public void detach(RecyclerView view) {
		Registration registration = registrations.remove(view);
		if (registration != null) registration.detach();
	}

	public void reset(RecyclerView view) {
		Registration registration = registrations.get(view);
		if (registration != null) registration.reset();
	}

	private abstract class Registration extends RecyclerView.OnScrollListener {
		final RecyclerView view;
		Registration(RecyclerView view) { this.view = view; }
		void attach() { view.addOnScrollListener(this); }
		void detach() { view.removeOnScrollListener(this); reset(); }
		abstract void reset();
	}

	private final class ThreadRegistration extends Registration implements ViewTreeObserver.OnPreDrawListener,
			View.OnAttachStateChangeListener, ViewTreeObserver.OnWindowFocusChangeListener {
		final ExpandedThreadScrollState state;
		final Rect edgeBounds = new Rect();
		ViewTreeObserver observer;
		ThreadRegistration(RecyclerView view) {
			super(view);
			state = new ExpandedThreadScrollState(Math.round(12 * ResourceUtils.obtainDensity(view)));
		}
		@Override void attach() {
			super.attach(); view.addOnAttachStateChangeListener(this);
			bindObserver();
		}
		@Override void detach() {
			clearObserver();
			view.removeOnAttachStateChangeListener(this); super.detach();
		}
		void clearObserver() {
			if (observer != null && observer.isAlive()) {
				observer.removeOnPreDrawListener(this); observer.removeOnWindowFocusChangeListener(this);
			}
			observer = null;
		}
		void bindObserver() {
			ViewTreeObserver next = view.getViewTreeObserver();
			if (observer == next) return;
			clearObserver(); observer = next;
			// A pre-attachment observer may have merged its listeners into this tree.
			observer.removeOnPreDrawListener(this); observer.removeOnWindowFocusChangeListener(this);
			observer.addOnPreDrawListener(this); observer.addOnWindowFocusChangeListener(this);
		}
		@Override void reset() { state.reset(); }
		@Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
			listener.onThreadScroll(view, state.scroll(dy, isThreadAtStart(view, edgeBounds)));
		}
		@Override public void onScrollStateChanged(@NonNull RecyclerView view, int newState) {
			if (newState == RecyclerView.SCROLL_STATE_IDLE) state.reset();
		}
		@Override public boolean onPreDraw() {
			if (view.isAttachedToWindow()) listener.onThreadScroll(view, state.scroll(0, isThreadAtStart(view, edgeBounds)));
			return true;
		}
		@Override public void onViewAttachedToWindow(View view) { state.reset(); bindObserver(); }
		@Override public void onViewDetachedFromWindow(View view) { state.reset(); clearObserver(); }
		@Override public void onWindowFocusChanged(boolean hasFocus) { if (!hasFocus) state.reset(); }
	}

	/** After layout, item zero must actually reach the padded top; a partial OP is insufficient. */
	static boolean isThreadAtStart(RecyclerView view) {
		return isThreadAtStart(view, new Rect());
	}

	private static boolean isThreadAtStart(RecyclerView view, Rect bounds) {
		if (!(view.getLayoutManager() instanceof LinearLayoutManager) || view.hasPendingAdapterUpdates()
				|| view.getAdapter() == null || view.getHeight() <= 0 || view.getChildCount() == 0) return false;
		LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
		if (manager.getOrientation() != RecyclerView.VERTICAL || manager.getReverseLayout()
				|| manager.getItemCount() != view.getAdapter().getItemCount()) return false;
		View first = manager.findViewByPosition(0);
		if (first == null || manager.findFirstVisibleItemPosition() != 0) return false;
		manager.getDecoratedBoundsWithMargins(first, bounds);
		// A short list is also at this actual top after layout, regardless of item count.
		// A single tall post can scroll, and only its unclipped top qualifies.
		return bounds.height() > 0 && bounds.top >= view.getPaddingTop();
	}

	/** Original thresholds, direction inference and first/last semantics for every other list. */
	private final class LegacyRegistration extends Registration implements View.OnLayoutChangeListener {
		boolean scrollingDown, scrollHandled;
		int firstPosition = -1, firstTop, slopDelta = -1, totalDelta;
		LegacyRegistration(RecyclerView view) { super(view); }
		@Override void attach() { super.attach(); view.addOnLayoutChangeListener(this); }
		@Override void detach() { view.removeOnLayoutChangeListener(this); super.detach(); }
		@Override void reset() { scrollingDown = scrollHandled = false; firstPosition = -1; firstTop = totalDelta = 0; }
		@Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
			track(dx, dy); scrollHandled = true;
		}
		void track(int dx, int dy) {
			if (slopDelta == -1) slopDelta = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
			LinearLayoutManager manager = (LinearLayoutManager) view.getLayoutManager();
			int count = manager.getItemCount(), firstPosition = manager.findFirstVisibleItemPosition();
			int lastPosition = manager.findLastVisibleItemPosition();
			boolean first, last;
			if (firstPosition < 0 || lastPosition < 0 || view.getChildCount() == 0) {
				first = last = scrollingDown = false; this.firstPosition = -1; firstTop = 0;
			} else {
				first = firstPosition == 0; last = lastPosition == count - 1;
				int firstTop = view.getChildAt(0).getTop();
				if (dx == 0 && dy == 0) {
					if (this.firstPosition != firstPosition || this.firstTop != firstTop) {
						totalDelta = 0;
						scrollingDown = this.firstPosition >= 0 && (firstPosition > this.firstPosition
								|| firstPosition == this.firstPosition && firstTop > this.firstTop);
					}
				} else if (dy != 0) {
					totalDelta += dy;
					if (Math.abs(totalDelta) > slopDelta) { scrollingDown = totalDelta > 0; totalDelta = 0; }
				}
				this.firstPosition = firstPosition; this.firstTop = firstTop;
			}
			listener.onScroll(view, scrollingDown, count, first, last);
		}
		@Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
				int oldLeft, int oldTop, int oldRight, int oldBottom) {
			if (scrollHandled) scrollHandled = false;
			else track(0, 0);
		}
	}

	public interface OnScrollListener {
		void onScroll(ViewGroup view, boolean scrollingDown, int totalItemCount, boolean first, boolean last);
		default void onThreadScroll(RecyclerView view, ExpandedThreadScrollState.Decision decision) {}
	}
}
