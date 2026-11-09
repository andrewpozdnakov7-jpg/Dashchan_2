package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
	private final Paint backgroundPaint = new Paint();
	private PostLinearLayout backgroundSource;
	private final Runnable backgroundInvalidation = this::invalidateBackground;
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
	private void invalidateBackground() { if (!disposed) list.invalidate(); }
	private void observeBackground(PostLinearLayout source) {
		if (backgroundSource != source) {
			if (backgroundSource != null) backgroundSource.setBackgroundExtensionInvalidation(null);
			backgroundSource = source;
			if (source != null) source.setBackgroundExtensionInvalidation(backgroundInvalidation);
		}
	}
	public void configure(boolean enabled, Rect boundsInList) {
		if (!enabled && !this.enabled) { fab.set(boundsInList); return; }
		if (!enabled) observeBackground(null);
		if (this.enabled != enabled || !fab.equals(boundsInList)) {
			this.enabled = enabled; fab.set(boundsInList); schedule();
		}
	}
	private void schedule() { if (!pending && !disposed) { pending = true; list.postOnAnimation(update); } }
	@Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
			@NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
		out.setEmpty();
		if (!enabled || fab.isEmpty() || !(parent.getAdapter() instanceof PostsAdapter)) return;
		PostsAdapter adapter = (PostsAdapter) parent.getAdapter();
		int position = parent.getChildAdapterPosition(view);
		// getItemCount is totalCount in windowed mode. Never reserve a placeholder or a window edge.
		if (position < 0 || position != adapter.getItemCount() - 1 || adapter.getItem(position) == null) return;
		RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) view.getLayoutParams();
		int gap = Math.round(8 * ResourceUtils.obtainDensity(parent));
		if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
			out.left = Math.max(0, fab.right + gap - parent.getPaddingLeft() - params.leftMargin);
		} else {
			out.right = Math.max(0, parent.getWidth() - parent.getPaddingRight() - params.rightMargin - fab.left + gap);
		}
		// The normal edge gap only: no FAB-height footer or change to list scroll/fast-scroll logic.
		out.bottom = gap;
	}

	@Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
			@NonNull RecyclerView.State state) {
		PostLinearLayout source = null;
		if (!disposed && enabled && !fab.isEmpty() && parent.getAdapter() instanceof PostsAdapter) {
			PostsAdapter adapter = (PostsAdapter) parent.getAdapter();
			int last = adapter.getItemCount() - 1;
			if (last >= 0 && adapter.getItem(last) != null) {
				for (int i = 0; i < parent.getChildCount(); i++) {
					View child = parent.getChildAt(i);
					if (child instanceof PostLinearLayout && child.getVisibility() == View.VISIBLE
							&& parent.getChildAdapterPosition(child) == last) {
						source = (PostLinearLayout) child;
						break;
					}
				}
			}
		}
		observeBackground(source);
		if (source == null) return;
		int color = source.getBackgroundExtensionColor();
		int alpha = Math.round(Color.alpha(color) * source.getAlpha());
		if (alpha == 0) return;
		RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) source.getLayoutParams();
		boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		float left = rtl ? parent.getPaddingLeft() + params.leftMargin - source.getLeft() : source.getWidth();
		float right = rtl ? 0 : parent.getWidth() - parent.getPaddingRight() - params.rightMargin - source.getLeft();
		if (right <= left) return;
		backgroundPaint.setColor(color & 0x00ffffff | alpha << 24);
		int save = canvas.save();
		canvas.clipRect(parent.getPaddingLeft(), 0, parent.getWidth() - parent.getPaddingRight(), parent.getHeight());
		canvas.translate(source.getLeft(), source.getTop());
		canvas.concat(source.getMatrix());
		// Same live fill and transform as the row; never draw text, ripple, or a second card.
		canvas.drawRect(left, 0, right, source.getHeight(), backgroundPaint);
		canvas.restoreToCount(save);
	}

	public void dispose() {
		disposed = true;
		observeBackground(null);
		list.removeCallbacks(update);
	}
}
