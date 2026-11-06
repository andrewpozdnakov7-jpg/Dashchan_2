package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.annotation.NonNull;
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
	private final ViewTreeObserver.OnGlobalLayoutListener visibilityListener = this::updateVisibility;
	private final RecyclerView.OnScrollListener edgeListener = new RecyclerView.OnScrollListener() {
		@Override public void onScrolled(@NonNull RecyclerView list, int dx, int dy) { updateEdges(); }
	};
	private boolean enabled, contentVisible, edgesReady;

	public ThreadQuickNavigation(PaddedRecyclerView recyclerView, boolean hideAtThreadEdges) {
		this(recyclerView, () -> recyclerView.getAdapter() != null
				&& recyclerView.getAdapter().getItemCount() > 0, bottom -> scroll(recyclerView, bottom),
				hideAtThreadEdges ? recyclerView : null);
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
		// A 48 dp drawable viewport (previously 24 dp), with 8 dp touch padding.
		int size = (int) (64f * density);
		ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
		for (int i = 0; i < 2; i++) {
			boolean bottom = i == 1;
			ImageButton button = new ImageButton(getContext(), null, android.R.attr.borderlessButtonStyle);
			button.setImageResource(R.drawable.ic_arrow_back);
			button.setScaleType(ImageView.ScaleType.FIT_CENTER);
			int padding = (int) (8f * density);
			button.setPadding(padding, padding, padding, padding);
			button.setRotation(bottom ? -90f : 90f);
			button.setImageTintList(ColorStateList.valueOf(theme.primary | 0xff000000));
			GradientDrawable background = new GradientDrawable();
			background.setShape(GradientDrawable.RECTANGLE);
			background.setCornerRadius(12f * density);
			background.setColor(theme.card);
			button.setBackground(background);
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
		String mode = Preferences.getThreadQuickNavigation();
		enabled = "left".equals(mode) || "right".equals(mode);
		for (int i = 0; i < buttons.getChildCount(); i++) {
			View button = buttons.getChildAt(i);
			InterfaceAppearance.quickNavigationIcon((ImageView) button);
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

	public void setContentVisible(boolean visible) {
		contentVisible = visible;
		updateVisibility();
	}

	private void updateVisibility() {
		WindowInsets insets = getRootWindowInsets();
		boolean visible = enabled && contentVisible && content.isShown() && hasContent.getAsBoolean()
				&& (insets == null || !insets.isVisible(WindowInsets.Type.ime()));
		// Keep the full-size container in layout so inset updates remain available.
		buttons.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
		if (visible) updateEdges(); else resetEdges();
	}

	private void updateEdges() {
		if (edgeList == null || buttons.getVisibility() != View.VISIBLE || !isAttachedToWindow()) return;
		// Use the same decorated post coordinates as the fast scroller, including partially visible rows.
		FastScrollGeometry geometry = edgeList.getFastScrollGeometry();
		if (geometry == null) return; // Wait for the RecyclerView layout, including restored scroll positions.
		double fromTop = geometry.start, fromBottom = geometry.itemCount - geometry.end;
		boolean onePost = geometry.itemCount == 1;
		updateEdge(0, shouldShowEdge(fromTop, fromBottom, edgeShown[0], onePost));
		updateEdge(1, shouldShowEdge(fromBottom, fromTop, edgeShown[1], onePost));
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
