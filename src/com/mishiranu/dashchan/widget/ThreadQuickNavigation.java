package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Non-modal overlay: only the two buttons consume touches. */
public final class ThreadQuickNavigation extends FrameLayout {
	private final View content;
	private final BooleanSupplier hasContent;
	private final Consumer<Boolean> scroll;
	private final FrameLayout buttons;
	private final PaddedRecyclerView edgeList;
	private final float edgeShift;
	private final boolean[] edgeShown = new boolean[2];
	private final Rect firstRowBounds = new Rect(), lastRowBounds = new Rect();
	private final ViewTreeObserver.OnGlobalLayoutListener visibilityListener = this::updateVisibility;
	private final RecyclerView.OnScrollListener edgeListener = new RecyclerView.OnScrollListener() {
		@Override public void onScrolled(@NonNull RecyclerView list, int dx, int dy) { updateEdges(); }
	};
	private boolean enabled, contentVisible, edgesReady;
	private boolean threadFab, threadFabOpen;
	private final Rect threadFabBounds = new Rect();
	private AppearanceColors arrowColors;

	public ThreadQuickNavigation(PaddedRecyclerView recyclerView, boolean hideAtEdges) {
		this(recyclerView, () -> recyclerView.getAdapter() != null
				&& recyclerView.getAdapter().getItemCount() > 0, bottom -> scroll(recyclerView, bottom),
				hideAtEdges ? recyclerView : null);
	}

	/** Shared controls for native threads and HTML/native local archives. */
	public ThreadQuickNavigation(View content, BooleanSupplier hasContent, Consumer<Boolean> scroll) {
		this(content, hasContent, scroll, null);
	}

	private ThreadQuickNavigation(View content, BooleanSupplier hasContent, Consumer<Boolean> scroll,
			PaddedRecyclerView edgeList) {
		super(content.getContext());
		this.content = content;
		this.hasContent = hasContent;
		this.scroll = scroll;
		this.edgeList = edgeList;
		buttons = new FrameLayout(getContext());
		float density = ResourceUtils.obtainDensity(getContext());
		edgeShift = 4f * density;
		if (edgeList != null) {
			// The existing 8 dp margins leave room for the brief shift towards the edge.
			setClipChildren(false);
			buttons.setClipChildren(false);
		}
		// Shared permanent appearance: 48 dp touch, 40 dp surface, 20 dp glyph.
		int size = dp(48);
		for (int i = 0; i < 2; i++) {
			boolean bottom = i == 1;
			ImageButton button = new ImageButton(getContext(), null, android.R.attr.borderlessButtonStyle);
			button.setImageResource(bottom ? R.drawable.ic_thread_jump_bottom_experimental
					: R.drawable.ic_thread_jump_top_experimental);
			button.setScaleType(ImageView.ScaleType.FIT_CENTER);
			int padding = dp(14);
			button.setPadding(padding, padding, padding, padding);
			button.setContentDescription(getContext().getString(bottom
					? R.string.thread_quick_navigation_bottom : R.string.thread_quick_navigation_top));
			int edge = i;
			button.setOnClickListener(v -> {
				if (contentVisible && hasContent.getAsBoolean() && (edgeList == null || edgeShown[edge])) {
					this.scroll.accept(bottom);
				}
			});
			if (edgeList != null) {
				setInteractive(button, false);
				setEdgeFrame(button, false, bottom ? edgeShift : -edgeShift);
			}
			LayoutParams params = new LayoutParams(size, size, Gravity.RIGHT | (bottom ? Gravity.BOTTOM : Gravity.TOP));
			buttons.addView(button, params);
		}
		LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
		params.leftMargin = params.rightMargin = (int) (8f * density);
		params.topMargin = params.bottomMargin = (int) (8f * density);
		addView(buttons, params);
		refreshPreferences();
	}

	public void refreshPreferences() {
		applyArrowAppearance();
		String mode = Preferences.getThreadQuickNavigation();
		enabled = "left".equals(mode) || "right".equals(mode);
		for (int i = 0; i < buttons.getChildCount(); i++) {
			View button = buttons.getChildAt(i);
			LayoutParams params = (LayoutParams) button.getLayoutParams();
			int gravity = ("left".equals(mode) ? Gravity.LEFT : Gravity.RIGHT)
					| (i == 0 ? Gravity.TOP : Gravity.BOTTOM);
			if (params.gravity != gravity) {
				params.gravity = gravity;
				button.setLayoutParams(params);
			}
		}
		buttons.setAlpha(1f - Preferences.getThreadQuickNavigationTransparency() / 100f);
		updateVisibility();
	}

	/** The experiment changes placement/occlusion only, never the shared arrow appearance. */
	public void setThreadFab(boolean enabled, boolean open, Rect bounds) {
		if (!enabled && !threadFab && !threadFabOpen) return;
		boolean changed = threadFab != enabled || threadFabOpen != open || !threadFabBounds.equals(bounds);
		if (!changed) return;
		threadFab = enabled; threadFabOpen = open; threadFabBounds.set(bounds);
		refreshPreferences();
	}
	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(this) * value); }
	private void applyArrowAppearance() {
		ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
		AppearanceColors colors = AppearanceColors.interfaceCompatibility(theme.base == ThemeEngine.Theme.Base.DARK,
				theme.window, theme.card, theme.accent, ResourceUtils.getColor(getContext(), android.R.attr.textColorPrimary),
				ResourceUtils.getColor(getContext(), R.attr.colorTextError));
		if (colors.equals(arrowColors)) return;
		arrowColors = colors;
		for (int i = 0; i < 2; i++) {
			ImageButton button = (ImageButton) buttons.getChildAt(i);
			GradientDrawable background = new GradientDrawable();
			background.setColor(colors.surfaceContainer);
			background.setCornerRadius(dp(14));
			button.setImageTintList(ColorStateList.valueOf(colors.onSurfaceVariant));
			android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(background, dp(4));
			button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.ripple), inset, null));
		}
	}
	private void layoutNavigationArrows() {
		boolean withFab = threadFab && !threadFabBounds.isEmpty();
		boolean left = "left".equals(Preferences.getThreadQuickNavigation());
		boolean fabLeft = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int parentLeft = getPaddingLeft() + dp(8), parentRight = getWidth() - getPaddingRight() - dp(8);
		int parentBottom = getHeight() - getPaddingBottom() - dp(8);
		int track = edgeList != null ? edgeList.getFastScrollerTouchWidth() : 0;
		for (int i = 0; i < 2; i++) {
			View button = buttons.getChildAt(i);
			LayoutParams params = (LayoutParams) button.getLayoutParams();
			int side = withFab && left == fabLeft
					? left ? threadFabBounds.centerX() - dp(24) - parentLeft : parentRight - threadFabBounds.centerX() - dp(24)
					: left == (getLayoutDirection() == LAYOUT_DIRECTION_RTL) ? Math.max(0, track - dp(8)) : 0;
			int bottom = i == 1 && withFab && left == fabLeft ? Math.max(0, parentBottom - threadFabBounds.top + dp(8)) : 0;
			int lm = left ? Math.max(0, side) : 0, rm = left ? 0 : Math.max(0, side);
			if (params.leftMargin != lm || params.rightMargin != rm || params.bottomMargin != bottom) {
				params.leftMargin = lm; params.rightMargin = rm; params.bottomMargin = bottom;
				button.setLayoutParams(params);
			}
		}
	}

	public void setContentVisible(boolean visible) {
		contentVisible = visible;
		updateVisibility();
	}

	private void updateVisibility() {
		layoutNavigationArrows();
		WindowInsets insets = getRootWindowInsets();
		boolean visible = enabled && !threadFabOpen && contentVisible && content.isShown() && hasContent.getAsBoolean()
				&& (insets == null || !insets.isVisible(WindowInsets.Type.ime()));
		// Keep the full-size container in layout so inset updates remain available.
		buttons.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
		if (visible) updateEdges(); else resetEdges();
	}

	private void updateEdges() {
		if (edgeList == null || buttons.getVisibility() != View.VISIBLE || !isAttachedToWindow()) return;
		if (edgeList.getLayoutManager() instanceof GridLayoutManager) {
			updateGridEdges((GridLayoutManager) edgeList.getLayoutManager());
			return;
		}
		// Use the same decorated post coordinates as the fast scroller, including partially visible rows.
		FastScrollGeometry geometry = edgeList.getFastScrollGeometry();
		if (geometry == null) return; // Wait for the RecyclerView layout, including restored scroll positions.
		double fromTop = geometry.start, fromBottom = geometry.itemCount - geometry.end;
		boolean onePost = geometry.itemCount == 1;
		updateEdge(0, shouldShowEdge(fromTop, fromBottom, edgeShown[0], onePost));
		updateEdge(1, shouldShowEdge(fromBottom, fromTop, edgeShown[1], onePost));
		edgesReady = true;
	}

	private void updateGridEdges(GridLayoutManager manager) {
		// Boards always use a grid manager, including their one-column list/card modes. Do not
		// repurpose fast-scroll geometry: its opt-in is deliberately narrower and affects the thumb.
		if (edgeList.isComputingLayout() || edgeList.hasPendingAdapterUpdates()
				|| manager.findFirstVisibleItemPosition() == RecyclerView.NO_POSITION
				|| manager.getItemCount() == 0 || edgeList.getHeight() <= 0) return;
		if (manager.getOrientation() != RecyclerView.VERTICAL || manager.getReverseLayout()) {
			// Preserve access to navigation if a future board layout has unsupported coordinates.
			updateEdge(0, true);
			updateEdge(1, true);
			edgesReady = true;
			return;
		}
		View first = manager.findViewByPosition(0);
		View last = manager.findViewByPosition(manager.getItemCount() - 1);
		if (first != null) manager.getDecoratedBoundsWithMargins(first, firstRowBounds);
		if (last != null) manager.getDecoratedBoundsWithMargins(last, lastRowBounds);
		if ((first != null && firstRowBounds.height() <= 0) || (last != null && lastRowBounds.height() <= 0)) return;
		// GridLayoutManager measures every cell to its row's height. Looking at the first/last
		// cell therefore measures whole edge rows, including full-span headers and short final rows.
		// No per-scroll walk of the adapter or span-group lookup is necessary.
		boolean singleRow = first != null && last != null && firstRowBounds.top == lastRowBounds.top;
		boolean topPassed = first == null || firstRowBounds.bottom <= edgeList.getPaddingTop();
		boolean bottomPassed = last == null || lastRowBounds.top >= edgeList.getHeight() - edgeList.getPaddingBottom();
		// Determine actual edges from laid-out bounds too: unequal/hidden card heights must not
		// make estimated RecyclerView scroll metrics hide a button in the middle of a board.
		boolean canGoUp = first == null || firstRowBounds.top < edgeList.getPaddingTop();
		boolean canGoDown = last == null || lastRowBounds.bottom > edgeList.getHeight() - edgeList.getPaddingBottom();
		updateEdge(0, canGoUp && (edgeShown[0] || topPassed || !canGoDown || singleRow));
		updateEdge(1, canGoDown && (edgeShown[1] || bottomPassed || !canGoUp || singleRow));
		edgesReady = true;
	}

	private static boolean shouldShowEdge(double distance, double opposite, boolean shown, boolean onePost) {
		// Hide at the edge. Once hidden, wait until the edge post has passed before revealing again.
		// Keep navigation useful in a tall single post or a thread shorter than this reveal threshold.
		return distance > 0 && (shown || distance >= 1 || opposite == 0 || onePost);
	}

	private void updateEdge(int index, boolean show) {
		if (edgeShown[index] != show) setEdge(index, show, edgesReady);
	}

	private void resetEdges() {
		if (!edgesReady) return;
		edgesReady = false;
		setEdge(0, false, false);
		setEdge(1, false, false);
	}

	private void setEdge(int index, boolean show, boolean animate) {
		edgeShown[index] = show;
		View button = buttons.getChildAt(index);
		button.animate().cancel();
		setInteractive(button, show);
		float shift = index == 0 ? -edgeShift : edgeShift;
		long duration = animate && InterfaceMotion.isEnabled() ? InterfaceMotion.duration(show ? 240 : 160) : 0;
		if (duration == 0) {
			setEdgeFrame(button, show, shift);
			return;
		}
		button.setVisibility(View.VISIBLE);
		float scale = show ? 1f : .94f;
		button.animate().alpha(show ? 1f : 0f).translationY(show ? 0f : shift).scaleX(scale).scaleY(scale)
				.setDuration(duration).setInterpolator(InterfaceMotion.STANDARD).withEndAction(() -> {
					if (edgeShown[index] == show) setEdgeFrame(button, show, shift);
				}).start();
	}

	private static void setInteractive(View button, boolean interactive) {
		button.setClickable(interactive);
		button.setFocusable(interactive);
		button.setImportantForAccessibility(interactive ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
				: View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		if (!interactive) {
			button.cancelPendingInputEvents();
			button.setPressed(false);
		}
	}

	private static void setEdgeFrame(View button, boolean shown, float shift) {
		button.setAlpha(shown ? 1f : 0f);
		button.setTranslationY(shown ? 0f : shift);
		button.setScaleX(shown ? 1f : .94f);
		button.setScaleY(shown ? 1f : .94f);
		button.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
	}

	public static void scroll(RecyclerView recyclerView, boolean bottom) {
		RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
		if (adapter == null || adapter.getItemCount() == 0) return;
		recyclerView.stopScroll();
		int position = bottom ? adapter.getItemCount() - 1 : 0;
		RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
		if (manager instanceof LinearLayoutManager) {
			ListViewUtils.smoothScrollToPosition(recyclerView, position);
		} else {
			recyclerView.scrollToPosition(position);
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnGlobalLayoutListener(visibilityListener);
		if (edgeList != null) edgeList.addOnScrollListener(edgeListener);
		updateVisibility();
	}

	@Override
	protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnGlobalLayoutListener(visibilityListener);
		if (edgeList != null) edgeList.removeOnScrollListener(edgeListener);
		resetEdges();
		super.onDetachedFromWindow();
	}
}
