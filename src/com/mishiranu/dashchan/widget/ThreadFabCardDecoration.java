package com.mishiranu.dashchan.widget;

import android.graphics.Rect;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.ui.navigator.adapter.PostsAdapter;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Side reservation participates in measurement, independent of scroll position and lastInList. */
public final class ThreadFabCardDecoration extends RecyclerView.ItemDecoration {
	private final PaddedRecyclerView list;
	private final Rect fab = new Rect();
	private boolean enabled, pending, disposed;
	private final Runnable update = this::updateGeometry;
	private void updateGeometry() {
		pending = false;
		if (disposed || !(list.getAdapter() instanceof PostsAdapter)) return;
		if (list.isComputingLayout() || list.hasPendingAdapterUpdates()) { schedule(); return; }
		ListPosition anchor = ListPosition.obtain(list, null);
		((PostsAdapter) list.getAdapter()).setThreadFabReservation(enabled);
		list.invalidateItemDecorations();
		if (anchor != null) anchor.apply(list);
	}
	public ThreadFabCardDecoration(PaddedRecyclerView list) { this.list = list; }
	public void configure(boolean enabled, Rect boundsInList) {
		if (!enabled && !this.enabled) { fab.set(boundsInList); return; }
		if (this.enabled != enabled || !fab.equals(boundsInList)) {
			this.enabled = enabled; fab.set(boundsInList); schedule();
		}
	}
	private void schedule() { if (!pending && !disposed) { pending = true; list.postOnAnimation(update); } }
	@Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
			@NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
		out.setEmpty();
		PostLinearLayout post = view instanceof PostLinearLayout ? (PostLinearLayout) view : null;
		if (disposed || !enabled || fab.isEmpty() || !(parent.getAdapter() instanceof PostsAdapter)) {
			if (post != null) post.setThreadFabEndReservation(0);
			return;
		}
		PostsAdapter adapter = (PostsAdapter) parent.getAdapter();
		int position = parent.getChildAdapterPosition(view);
		// getItemCount is totalCount in windowed mode. Never reserve a placeholder or a window edge.
		if (position < 0 || position != adapter.getItemCount() - 1 || adapter.getItem(position) == null) {
			if (post != null) post.setThreadFabEndReservation(0);
			return;
		}
		RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
		int gap = Math.round(8 * ResourceUtils.obtainDensity(parent));
		int reservation;
		if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
			reservation = Math.max(0, fab.right + gap - parent.getPaddingLeft() - params.leftMargin);
		} else {
			reservation = Math.max(0, parent.getWidth() - parent.getPaddingRight() - params.rightMargin - fab.left + gap);
		}
		if (post != null) post.setThreadFabEndReservation(reservation);
		else if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) out.left = reservation;
		else out.right = reservation;
		// The normal edge gap only: no FAB-height footer or change to list scroll/fast-scroll logic.
		out.bottom = gap;
	}

	public void dispose() {
		disposed = true;
		for (int i = 0; i < list.getChildCount(); i++) {
			View child = list.getChildAt(i);
			if (child instanceof PostLinearLayout) ((PostLinearLayout) child).setThreadFabEndReservation(0);
		}
		list.removeCallbacks(update);
	}
}
