package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseIntArray;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.ImageLoader;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.graphics.SelectorCheckDrawable;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.SearchImageDialog;
import com.mishiranu.dashchan.util.AnimationUtils;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.EdgeEffectHandler;
import com.mishiranu.dashchan.widget.InsetsLayout;
import com.mishiranu.dashchan.widget.ListPosition;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import com.mishiranu.dashchan.widget.PullableWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ListUnit implements ActionMode.Callback {
	private static final int GRID_SPACING_DP = 4;

	private final GalleryInstance instance;

	private final PaddedRecyclerView recyclerView;
	private final Runnable updateGridMetricsRunnable;
	private final SparseIntArray selected = new SparseIntArray();

	private ActionMode selectionMode;
	private boolean diagnosticLayoutPending;
	private int diagnosticTarget = RecyclerView.NO_POSITION;
	private ListPosition pendingListPosition;
	private int gridMetricsWidth;
	private GalleryItem pendingReturnItem;
	private GalleryItem openedFromGridItem;
	private int openedGridWidth;
	private int openedGridHeight;
	private boolean gridVisible;
	private GalleryViewportMemory.Snapshot viewport;
	private GalleryViewportMemory.Snapshot pendingViewport;
	private GalleryItem viewportItem;

	public ListUnit(GalleryInstance instance) {
		this.instance = instance;
		float density = ResourceUtils.obtainDensity(instance.context);
		int spacing = (int) (GRID_SPACING_DP * density);
		recyclerView = new GalleryRecyclerView(instance.context, spacing);
		updateGridMetricsRunnable = () -> updateGridMetrics(recyclerView.getResources().getConfiguration());
		recyclerView.setId(android.R.id.list);
		recyclerView.setMotionEventSplittingEnabled(false);
		recyclerView.setClipToPadding(false);
		recyclerView.setLayoutManager(new GridLayoutManager(recyclerView.getContext(), 1));
		recyclerView.addItemDecoration(new SpacingItemDecoration(spacing));
		GridAdapter adapter = new GridAdapter(callback, instance.chanName, instance.galleryItems);
		recyclerView.setAdapter(adapter);
		recyclerView.getPullable().setPullSides(instance.callback.canRefreshGallery()
				? PullableWrapper.Side.BOTH : PullableWrapper.Side.NONE);
		recyclerView.getPullable().setOnPullListener((wrapper, side) -> instance.callback.refreshGallery());
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView view, int newState) {
				if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
					// A user's scroll takes priority over a previous navigation request.
					pendingListPosition = null;
					pendingReturnItem = null;
					pendingViewport = null;
				}
				logPosition("scroll_state=" + newState);
			}
		});
		recyclerView.getViewTreeObserver().addOnPreDrawListener(() -> {
			if (pendingListPosition != null && isGridLayoutSettled()) {
				logPosition("scroll_settled index=" + pendingListPosition.position);
				pendingListPosition = null;
			}
			if (pendingViewport != null && isGridLayoutSettled()) {
				GalleryViewportMemory.Snapshot snapshot = pendingViewport;
				pendingViewport = null;
				int anchor = findViewportItem(snapshot);
				if (anchor >= 0) {
					GalleryItem returnItem = pendingReturnItem;
					GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
					int offset = snapshot.offsetFor(getGridWidth(), manager.getSpanCount());
					scrollListToPosition(anchor, offset);
					pendingReturnItem = returnItem;
					logPosition("grid_viewport_restored index=" + anchor + " offset=" + offset);
					return false;
				}
			}
			if (pendingReturnItem != null && isGridLayoutSettled()) {
				GalleryItem item = pendingReturnItem;
				pendingReturnItem = null;
				int position = instance.galleryItems.indexOf(item);
				GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
				boolean unchanged = item == openedFromGridItem
						&& openedGridWidth == recyclerView.getWidth()
						&& openedGridHeight == recyclerView.getHeight();
				if (GalleryGridReturnPolicy.shouldReveal(position, manager.findFirstVisibleItemPosition(),
						manager.findLastVisibleItemPosition(), unchanged)) {
					logPosition("grid_return_reveal index=" + position);
					scrollListToPosition(position, 0);
					// Resolve the requested row before painting, rather than showing a jump.
					return false;
				}
				logPosition("grid_return_preserved index=" + position + " unchanged=" + unchanged);
			}
			if (diagnosticLayoutPending && recyclerView.isShown()) {
				diagnosticLayoutPending = false;
				logPosition("grid_pre_draw");
			}
			captureViewport();
			return true;
		});
		recyclerView.addOnLayoutChangeListener((view, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft) {
				// Recalculate after layout, outside RecyclerView's layout/scroll dispatch.
				scheduleGridMetricsUpdate();
			}
		});
		updateGridMetrics(instance.context.getResources().getConfiguration());
	}

	public void setRefreshColor(int color) {
		recyclerView.getPullable().setColor(color);
	}

	private final GridAdapter.Callback callback = new GridAdapter.Callback() {
		@Override
		public boolean isItemChecked(int position) {
			return selected.indexOfKey(position) >= 0;
		}

		@Override
		public void onItemClick(View view, int position) {
			ListUnit.this.onItemClick(view, position);
		}

		@Override
		public boolean onItemLongClick(int position) {
			return ListUnit.this.onItemLongClick(position);
		}
	};

	public RecyclerView getRecyclerView() {
		return recyclerView;
	}

	private GridAdapter getAdapter() {
		return (GridAdapter) recyclerView.getAdapter();
	}

	public int[] getSelectedPositions() {
		if (selectionMode != null) {
			SparseIntArray array = new SparseIntArray();
			int count = getAdapter().getItemCount();
			for (int i = 0; i < count; i++) {
				if (callback.isItemChecked(i)) {
					array.put(i, i);
				}
			}
			int[] result = new int[array.size()];
			for (int i = 0; i < array.size(); i++) {
				result[i] = array.keyAt(i);
			}
			return result;
		}
		return null;
	}

	public void scrollListToPosition(int position, boolean checkVisibility) {
		if (checkVisibility) {
			requestReturnToGrid(position);
			return;
		}
		scrollListToPosition(position, 0);
	}

	void onPageOpenedFromGrid(int position) {
		recyclerView.stopScroll();
		captureViewport();
		pendingReturnItem = null;
		openedFromGridItem = position >= 0 && position < instance.galleryItems.size()
				? instance.galleryItems.get(position) : null;
		openedGridWidth = recyclerView.getWidth();
		openedGridHeight = recyclerView.getHeight();
		logPosition("grid_open_page index=" + position);
	}

	void requestReturnToGrid(int position) {
		diagnosticTarget = position;
		diagnosticLayoutPending = true;
		if (viewport == null || findViewportItem(viewport) < 0) {
			// The first swipe from a thread image is INITIAL ENTRY, not a return.
			// It must locate that image even when automatic following is disabled.
			viewport = null;
			logPosition("grid_initial_entry index=" + position);
			scrollListToPosition(position, 0);
			return;
		}
		pendingViewport = viewport;
		if (!Preferences.isScrollGalleryToCurrentFile()) {
			// Restore the old anchor AND offset, not the hidden LayoutManager's anchor.
			pendingReturnItem = null;
			logPosition("grid_return_preserved autoScroll=false index=" + position);
			recyclerView.invalidate();
			return;
		}
		pendingReturnItem = position >= 0 && position < instance.galleryItems.size()
				? instance.galleryItems.get(position) : null;
		// Showing a GONE view requests layout. Its old visibility indices must not
		// decide whether to scroll. Keep the existing LayoutManager viewport and
		// reveal only an off-screen item once layout and adapter updates have settled.
		logPosition("grid_return_requested index=" + position);
		recyclerView.invalidate();
	}

	private String viewportItemKey(GalleryItem item) {
		return item.postNumber + "|" + item.getFileUri(Chan.get(instance.chanName));
	}

	private int findViewportItem(GalleryViewportMemory.Snapshot snapshot) {
		for (int i = 0; i < instance.galleryItems.size(); i++) {
			if (snapshot.itemKey.equals(viewportItemKey(instance.galleryItems.get(i)))) return i;
		}
		return -1;
	}

	private void captureViewport() {
		// GONE/animating-away grids and unsettled targets are not a user's viewport.
		if (!gridVisible || !isGridLayoutSettled() || pendingViewport != null
				|| pendingListPosition != null || pendingReturnItem != null) return;
		ListPosition position = ListPosition.obtain(recyclerView, null);
		if (position != null && position.position >= 0 && position.position < instance.galleryItems.size()) {
			GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
			GalleryItem item = instance.galleryItems.get(position.position);
			if (viewport != null && item == viewportItem && viewport.offset == position.offset
					&& viewport.width == getGridWidth() && viewport.columns == manager.getSpanCount()) return;
			viewportItem = item;
			viewport = new GalleryViewportMemory.Snapshot(viewportItemKey(item),
					position.offset, getGridWidth(), manager.getSpanCount());
		}
	}

	GalleryViewportMemory.Snapshot getViewport() {
		captureViewport();
		return viewport;
	}

	boolean setViewport(GalleryViewportMemory.Snapshot snapshot) {
		if (snapshot == null || !snapshot.isValid() || findViewportItem(snapshot) < 0) return false;
		viewport = snapshot;
		pendingViewport = snapshot;
		return true;
	}

	void initializeGridPosition(int position) {
		if (viewport != null) {
			pendingViewport = viewport;
			if (Preferences.isScrollGalleryToCurrentFile() && position >= 0 && position < instance.galleryItems.size()) {
				pendingReturnItem = instance.galleryItems.get(position);
			}
		}
		else if (position >= 0) scrollListToPosition(position, 0);
	}

	private boolean isGridLayoutSettled() {
		return recyclerView.isShown() && recyclerView.getWidth() > 0 && recyclerView.getHeight() > 0
				&& gridMetricsWidth == getGridWidth() && !recyclerView.isLayoutRequested()
				&& !recyclerView.hasPendingAdapterUpdates() && !recyclerView.isComputingLayout();
	}

	void restoreFilterPosition(int position, int offset) {
		scrollListToPosition(position, offset);
	}

	private void scrollListToPosition(int position, int offset) {
		pendingReturnItem = null;
		pendingViewport = null;
		diagnosticTarget = position;
		diagnosticLayoutPending = true;
		logPosition("scroll_request explicit=true offset=" + offset);
		if (position < 0 || position >= instance.galleryItems.size()) {
			logPosition("scroll_rejected_invalid_index");
			return;
		}
		recyclerView.stopScroll();
		updateGridMetrics(recyclerView.getResources().getConfiguration());
		// Keep the explicit target through the initial one-column layout and the later
		// measured grid layout. GridLayoutManager's implicit anchor can change between them.
		pendingListPosition = new ListPosition(position, offset);
		pendingListPosition.apply(recyclerView);
		logPosition("scroll_submitted");
	}

	static final class FilterViewport {
		final GalleryItem item;
		final int offset;
		final boolean atStart;
		final boolean atEnd;

		FilterViewport(GalleryItem item, int offset, boolean atStart, boolean atEnd) {
			this.item = item;
			this.offset = offset;
			this.atStart = atStart;
			this.atEnd = atEnd;
		}
	}

	FilterViewport captureFilterViewport() {
		recyclerView.stopScroll();
		boolean settled = recyclerView.isShown() && recyclerView.getChildCount() > 0
				&& !recyclerView.isLayoutRequested() && !recyclerView.hasPendingAdapterUpdates();
		ListPosition position = !settled && pendingListPosition != null
				? pendingListPosition : ListPosition.obtain(recyclerView, null);
		if (position == null || position.position < 0 || position.position >= instance.galleryItems.size()) {
			return null;
		}
		return new FilterViewport(instance.galleryItems.get(position.position), position.offset,
				settled && !recyclerView.canScrollVertically(-1),
				settled && !recyclerView.canScrollVertically(1));
	}

	void logPosition(String event) {
		GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
		int first = manager.findFirstVisibleItemPosition();
		View firstView = first >= 0 ? manager.findViewByPosition(first) : null;
		instance.logNavigation(event + " target=" + diagnosticTarget + " count=" + getAdapter().getItemCount()
				+ " first=" + first + " last=" + manager.findLastVisibleItemPosition()
				+ " fullFirst=" + manager.findFirstCompletelyVisibleItemPosition()
				+ " fullLast=" + manager.findLastCompletelyVisibleItemPosition()
				+ " firstTop=" + (firstView != null ? manager.getDecoratedTop(firstView) : "none")
				+ " paddingTop=" + recyclerView.getPaddingTop() + " columns=" + manager.getSpanCount()
				+ " size=" + recyclerView.getWidth() + "x" + recyclerView.getHeight()
				+ " children=" + recyclerView.getChildCount() + " shown=" + recyclerView.isShown()
				+ " layoutRequested=" + recyclerView.isLayoutRequested()
				+ " pendingUpdates=" + recyclerView.hasPendingAdapterUpdates()
				+ " computingLayout=" + recyclerView.isComputingLayout());
	}

	public boolean areItemsSelectable() {
		return getAdapter().getItemCount() > 0;
	}

	public void onGalleryItemsChanged() {
		pendingListPosition = null;
		pendingReturnItem = null;
		openedFromGridItem = null;
		viewport = null;
		pendingViewport = null;
		viewportItem = null;
		if (selectionMode != null) {
			selectionMode.finish();
		} else {
			selected.clear();
		}
		getAdapter().notifyDataSetChanged();
	}

	public void setRefreshBusy(boolean busy) {
		if (busy) recyclerView.getPullable().startBusyState(PullableWrapper.Side.TOP);
		else recyclerView.getPullable().cancelBusyState();
	}

	public void onGalleryItemsRefreshed(List<GalleryItem> oldItems) {
		ListPosition anchor = ListPosition.obtain(recyclerView, null);
		ArrayList<GalleryItem> selectedItems = new ArrayList<>();
		for (int i = 0; i < selected.size(); i++) {
			int position = selected.keyAt(i);
			if (position < oldItems.size()) selectedItems.add(oldItems.get(position));
		}
		selected.clear();
		for (GalleryItem item : selectedItems) {
			int index = instance.galleryItems.indexOf(item);
			if (index >= 0) selected.put(index, index);
		}
		DiffUtil.DiffResult diff;
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Diff/gallery/calculate")
				.reason("refresh").sizes(oldItems.size(), instance.galleryItems.size())) {
			diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override public int getOldListSize() { return oldItems.size(); }
				@Override public int getNewListSize() { return instance.galleryItems.size(); }
				@Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
					return oldItems.get(oldPosition) == instance.galleryItems.get(newPosition);
				}
				@Override public boolean areContentsTheSame(int oldPosition, int newPosition) { return true; }
			});
			scope.result("ok");
		}
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Diff/gallery/dispatch")
				.reason("refresh").sizes(oldItems.size(), instance.galleryItems.size())) {
			diff.dispatchUpdatesTo(getAdapter());
			scope.result("ok");
		}
		if (anchor != null && anchor.position < oldItems.size()) {
			int index = instance.galleryItems.indexOf(oldItems.get(anchor.position));
			if (index >= 0) {
				pendingListPosition = new ListPosition(index, anchor.offset);
				pendingListPosition.apply(recyclerView);
			}
		}
		updateAllGalleryItemsChecked();
	}

	public void startSelectionMode(int[] selected) {
		this.selected.clear();
		selectionMode = recyclerView.startActionMode(this);
		if (selectionMode != null && selected != null) {
			int selectedCount = 0;
			int count = getAdapter().getItemCount();
			for (int position : selected) {
				if (position >= 0 && position < count) {
					this.selected.append(position, position);
					selectedCount++;
				}
			}
			updateAllGalleryItemsChecked();
			selectionMode.setTitle(ResourceUtils.getColonString(instance.context.getResources(),
					R.string.selected, selectedCount));
		}
	}

	public boolean onApplyWindowInsets(InsetsLayout.Insets insets) {
		int top = insets.top + getActionBarHeight();
		ViewUtils.setNewMargin(recyclerView, insets.left, null, insets.right, null);
		ViewUtils.setNewPadding(recyclerView, null, top, null, insets.bottom);
		return true;
	}

	private static final float GRID_SCALE = 1.1f;

	public void switchMode(boolean galleryMode, int duration) {
		if (!galleryMode) captureViewport();
		gridVisible = galleryMode;
		diagnosticLayoutPending = true;
		logPosition("grid_switch visible=" + galleryMode + " duration=" + duration);
		if (galleryMode) {
			recyclerView.setVisibility(View.VISIBLE);
			getAdapter().activate();
			if (duration > 0) {
				recyclerView.setAlpha(0f);
				recyclerView.setScaleX(GRID_SCALE);
				recyclerView.setScaleY(GRID_SCALE);
				recyclerView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).setListener(null).start();
			}
		} else {
			pendingReturnItem = null;
			if (duration > 0) {
				recyclerView.setAlpha(1f);
				recyclerView.setScaleX(1f);
				recyclerView.setScaleY(1f);
				recyclerView.animate().alpha(0f).scaleX(GRID_SCALE).scaleY(GRID_SCALE).setDuration(duration)
						.setListener(new AnimationUtils.VisibilityListener(recyclerView, View.GONE)).start();
			} else {
				recyclerView.setVisibility(View.GONE);
			}
		}
	}

	private void onItemClick(View view, int position) {
		instance.logNavigation("grid_click index=" + position + " selection=" + (selectionMode != null));
		if (selectionMode != null) {
			int index = selected.indexOfKey(position);
			if (index >= 0) {
				selected.removeAt(index);
			} else {
				selected.put(position, position);
			}
			updateGalleryItemChecked(view, position);
			selectionMode.setTitle(ResourceUtils.getColonString(instance.context.getResources(),
					R.string.selected, selected.size()));
		} else {
			instance.callback.navigatePageFromList(position);
		}
	}

	private boolean onItemLongClick(int position) {
		if (selectionMode != null) {
			return false;
		}
		showItemMenu(instance.callback.getChildFragmentManager(), instance.chanName,
				getAdapter().getItem(position), instance.callback.isAllowNavigatePostManually(false));
		return true;
	}

	private static void showItemMenu(FragmentManager fragmentManager,
			String chanName, GalleryItem galleryItem, boolean allowNavigatePostManually) {
		new InstanceDialog(fragmentManager, null, provider -> createItemMenu(provider,
				chanName, galleryItem, allowNavigatePostManually));
	}

	private static AlertDialog createItemMenu(InstanceDialog.Provider provider,
			String chanName, GalleryItem galleryItem, boolean allowNavigatePostManually) {
		GalleryInstance.Callback callback = GalleryInstance.getCallback(provider);
		Context context = callback.getWindow().getContext();
		Chan chan = Chan.get(chanName);
		DialogMenu dialogMenu = new DialogMenu(context);
		dialogMenu.setTitle(!StringUtils.isEmpty(galleryItem.originalName)
				? galleryItem.originalName : galleryItem.getFileName(chan));
		dialogMenu.add(R.string.download_file, () -> callback.downloadGalleryItem(galleryItem));
		if (galleryItem.getDisplayImageUri(chan) != null) {
			dialogMenu.add(R.string.search_image, () -> new SearchImageDialog(chanName,
					galleryItem.getDisplayImageUri(chan), galleryItem.getThumbnailUri(chan))
					.show(provider.getFragmentManager(), null));
		}
		dialogMenu.add(R.string.copy_link, () -> StringUtils.copyToClipboard(context,
				galleryItem.getFileUri(chan).toString()));
		if (allowNavigatePostManually && galleryItem.postNumber != null) {
			dialogMenu.add(R.string.go_to_post, () -> callback.navigatePost(galleryItem, true, true));
		}
		dialogMenu.add(R.string.share_link, () -> NavigationUtils.shareLink(context, null,
				galleryItem.getFileUri(chan)));
		return dialogMenu.create();
	}

	public void onConfigurationChanged(Configuration newConfig) {
		if (newConfig.orientation != Configuration.ORIENTATION_UNDEFINED) {
			updateGridMetrics(newConfig);
		}
	}

	private void updateGalleryItemChecked(View view, int position) {
		boolean checked = callback.isItemChecked(position);
		GridAdapter.ViewHolder holder = (GridAdapter.ViewHolder) recyclerView.getChildViewHolder(view);
		holder.selectorCheckDrawable.setSelected(checked, true);
	}

	private void updateAllGalleryItemsChecked() {
		int childCount = recyclerView.getChildCount();
		for (int i = 0; i < childCount; i++) {
			View view = recyclerView.getChildAt(i);
			int position = recyclerView.getChildAdapterPosition(view);
			updateGalleryItemChecked(view, position);
		}
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		selected.clear();
		mode.setTitle(ResourceUtils.getColonString(instance.context.getResources(), R.string.selected, 0));
		menu.add(0, R.id.menu_select_all, 0, R.string.select_all)
				.setIcon(ResourceUtils.getActionBarIcon(instance.context, R.attr.iconActionSelectAll))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, R.id.menu_download, 0, R.string.download_files)
				.setIcon(ResourceUtils.getActionBarIcon(instance.context, R.attr.iconActionDownload))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		return true;
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		return false;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		if (item.getItemId() == R.id.menu_select_all) {
			int count = getAdapter().getItemCount();
			for (int i = 0; i < count; i++) {
				selected.put(i, i);
			}
			selectionMode.setTitle(ResourceUtils.getColonString(instance.context.getResources(),
					R.string.selected, count));
			updateAllGalleryItemsChecked();
			return true;
		} else if (item.getItemId() == R.id.menu_download) {
			ArrayList<GalleryItem> galleryItems = new ArrayList<>();
			GridAdapter adapter = getAdapter();
			for (int i = 0; i < adapter.getItemCount(); i++) {
				if (callback.isItemChecked(i)) {
					galleryItems.add(adapter.getItem(i));
				}
			}
			instance.callback.downloadGalleryItems(galleryItems);
			mode.finish();
			return true;
		}
		return false;
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {
		selectionMode = null;
		selected.clear();
		updateAllGalleryItemsChecked();
	}

	private int getActionBarHeight() {
		TypedArray typedArray = instance.context.obtainStyledAttributes(new int[] {android.R.attr.actionBarSize});
		try {
			return typedArray.getDimensionPixelSize(0, 0);
		} finally {
			typedArray.recycle();
		}
	}

	private void scheduleGridMetricsUpdate() {
		recyclerView.removeCallbacks(updateGridMetricsRunnable);
		recyclerView.post(updateGridMetricsRunnable);
	}

	private int getGridWidth() {
		return recyclerView.getWidth() - recyclerView.getPaddingLeft() - recyclerView.getPaddingRight();
	}

	private void updateGridMetrics(Configuration configuration) {
		// A restored gallery can coexist with a narrow PiP window. WindowManager metrics
		// need not describe this list, so keep the initial single column until it is laid out.
		int width = getGridWidth();
		if (width <= 0) {
			return;
		}
		if (recyclerView.isComputingLayout()) {
			scheduleGridMetricsUpdate();
			return;
		}
		// Items count in row must fit to this inequality: (widthDp - (i + 1) * GRID_SPACING_DP) / i >= SIZE
		// Where SIZE - size of item in grid, i - items count in row, unknown quantity
		// The solution is: i <= (widthDp - GRID_SPACING_DP) / (SIZE + GRID_SPACING_DP)
		int widthDp = (int) (width / ResourceUtils.obtainDensity(recyclerView));
		int size = ResourceUtils.isTablet(configuration) ? 160 : 100;
		int spanCount = Math.max(1, (widthDp - GRID_SPACING_DP) / (size + GRID_SPACING_DP));
		gridMetricsWidth = width;
		GridLayoutManager layoutManager = (GridLayoutManager) recyclerView.getLayoutManager();
		VideoDiagnostics.recordUi("gallery_grid widthPx=" + width + " widthDp=" + widthDp
				+ " columns=" + spanCount + " previousColumns=" + layoutManager.getSpanCount());
		if (layoutManager.getSpanCount() != spanCount) {
			ListPosition anchor = pendingListPosition;
			if (anchor == null) {
				anchor = ListPosition.obtain(recyclerView, null);
				if (anchor != null) {
					// Old pixel offsets may exceed the height of a row in the new grid.
					// Keep the current first visible item, not an obsolete navigation target.
					anchor = new ListPosition(anchor.position, 0);
				}
			}
			diagnosticLayoutPending = true;
			logPosition("grid_columns_change next=" + spanCount);
			layoutManager.setSpanCount(spanCount);
			if (anchor != null && anchor.position >= 0 && anchor.position < instance.galleryItems.size()) {
				pendingListPosition = anchor;
				anchor.apply(recyclerView);
				logPosition("grid_anchor_reapplied index=" + anchor.position + " offset=" + anchor.offset);
			}
		}
	}

	private static class GalleryRecyclerView extends PaddedRecyclerView {
		private final int spacing;

		private final Rect rect = new Rect();
		private final Paint paint = new Paint();

		public GalleryRecyclerView(Context context, int spacing) {
			super(context);
			this.spacing = spacing;
			setVerticalScrollBarEnabled(true);
		}

		@Override
		protected void onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar, int l, int t, int r, int b) {
			int spacing = this.spacing;
			int thickness = (int) (spacing * 2f / 3f + 0.5f);
			Rect rect = this.rect;
			if (l == 0) {
				rect.left = 0;
				rect.right = thickness;
			} else if (r == getWidth()) {
				rect.left = r - thickness;
				rect.right = r;
			} else {
				return;
			}
			if (b - t == getHeight()) {
				t += getEdgeEffectShift(EdgeEffectHandler.Side.TOP);
				b -= getEdgeEffectShift(EdgeEffectHandler.Side.BOTTOM);
			}
			t += spacing;
			b -= spacing;
			int range = computeVerticalScrollRange();
			if (range > 0) {
				int height = b - t;
				int extent = computeVerticalScrollExtent();
				int length = (int) (height * ((float) extent / range) + 0.5f);
				length = Math.max(length, 2 * spacing);
				int offset = (int) ((height - length) * ((float) computeVerticalScrollOffset() /
						(range - extent)) + 0.5f);
				if (length > 0) {
					rect.top = t + offset;
					rect.bottom = t + offset + length;
					paint.setColor(Color.argb(0x7f * scrollBar.getAlpha() / 0xff,
							0xff, 0xff, 0xff));
					canvas.drawRect(rect, paint);
				}
			}
		}
	}

	private static class GridAdapter extends RecyclerView.Adapter<GridAdapter.ViewHolder> {
		public interface Callback extends ListViewUtils.ClickCallback<Void, ViewHolder> {
			boolean isItemChecked(int position);
			void onItemClick(View view, int position);
			boolean onItemLongClick(int position);

			@Override
			default boolean onItemClick(ViewHolder holder, int position, Void nothing, boolean longClick) {
				if (longClick) {
					return onItemLongClick(position);
				} else {
					onItemClick(holder.itemView, position);
					return true;
				}
			}
		}

		private static class ViewHolder extends RecyclerView.ViewHolder {
			public final AttachmentView thumbnail;
			public final TextView attachmentInfo;
			public final SelectorCheckDrawable selectorCheckDrawable;

			public ViewHolder(ViewGroup parent, Callback callback) {
				super(new FrameLayout(parent.getContext()) {
					@Override
					protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
						super.onMeasure(widthMeasureSpec, widthMeasureSpec);
					}
				});

				FrameLayout layout = (FrameLayout) itemView;
				layout.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
						RecyclerView.LayoutParams.WRAP_CONTENT));
				LayoutInflater.from(layout.getContext()).inflate(R.layout.list_item_attachment, layout);
				FrameLayout child = (FrameLayout) layout.getChildAt(0);
				child.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
				child.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
				thumbnail = itemView.findViewById(R.id.thumbnail);
				thumbnail.setBackgroundColor(0xff333333);
				thumbnail.setCropEnabled(true);
				attachmentInfo = itemView.findViewById(R.id.attachment_info);
				attachmentInfo.setBackgroundColor(0xaa111111);
				attachmentInfo.setGravity(Gravity.CENTER);
				attachmentInfo.setSingleLine(true);
				attachmentInfo.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
				ListViewUtils.bind(this, itemView.findViewById(R.id.attachment_click), true, null, callback);
				selectorCheckDrawable = new SelectorCheckDrawable();
				child.setForeground(selectorCheckDrawable);
			}
		}

		private final Callback callback;
		private final String chanName;
		private final List<GalleryItem> galleryItems;

		private boolean enabled = false;

		public GridAdapter(Callback callback, String chanName, List<GalleryItem> galleryItems) {
			this.callback = callback;
			this.chanName = chanName;
			this.galleryItems = galleryItems;
		}

		public void activate() {
			enabled = true;
		}

		@Override
		public int getItemCount() {
			return enabled ? galleryItems.size() : 0;
		}

		public GalleryItem getItem(int position) {
			return galleryItems.get(position);
		}

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(parent, callback);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			GalleryItem galleryItem = getItem(position);
			Chan chan = Chan.get(chanName);
			String extension = StringUtils.getFileExtension(galleryItem.getFileName(chan));
			StringBuilder attachmentInfo = new StringBuilder();
			if (!StringUtils.isEmpty(extension)) {
				attachmentInfo.append(extension.toUpperCase(Locale.getDefault()));
			}
			if (galleryItem.size > 0) {
				if (attachmentInfo.length() > 0) {
					attachmentInfo.append(' ');
				}
				attachmentInfo.append(StringUtils.formatFileSize(galleryItem.size, true));
			}
			holder.attachmentInfo.setText(attachmentInfo);
			Uri thumbnailUri = galleryItem.getThumbnailUri(chan);
			if (thumbnailUri != null) {
				CacheManager cacheManager = CacheManager.getInstance();
				String key = cacheManager.getCachedFileKey(thumbnailUri);
				holder.thumbnail.resetImage(key, AttachmentView.Overlay.NONE);
				ImageLoader.getInstance().loadImage(chan, thumbnailUri, key, false, holder.thumbnail);
			} else {
				holder.thumbnail.resetImage(null, AttachmentView.Overlay.WARNING);
				ImageLoader.getInstance().cancel(holder.thumbnail);
			}
			boolean checked = callback.isItemChecked(position);
			holder.selectorCheckDrawable.setSelected(checked, false);
		}
	}

	private static class SpacingItemDecoration extends RecyclerView.ItemDecoration {
		private final int spacing;

		public SpacingItemDecoration(int spacing) {
			this.spacing = spacing;
		}

		@Override
		public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
				@NonNull RecyclerView.State state) {
			int position = parent.getChildAdapterPosition(view);
			int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex();
			int columns = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
			int left;
			int right;
			if (columns >= 2) {
				int total = (columns + 1) * spacing;
				float average = (float) total / columns;
				left = (int) AnimationUtils.lerp(spacing, average - spacing, (float) column / (columns - 1));
				right = (int) average - left;
			} else {
				left = spacing;
				right = spacing;
			}
			boolean firstRow = position - column == 0;
			outRect.set(left, firstRow ? spacing : 0, right, spacing);
		}
	}
}
