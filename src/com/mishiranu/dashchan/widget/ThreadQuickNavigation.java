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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Non-modal overlay: only the two buttons consume touches. */
public final class ThreadQuickNavigation extends FrameLayout {
	private final RecyclerView recyclerView;
	private final FrameLayout buttons;
	private final ViewTreeObserver.OnGlobalLayoutListener visibilityListener = this::updateVisibility;
	private boolean enabled;
	private boolean contentVisible;

	public ThreadQuickNavigation(RecyclerView recyclerView) {
		super(recyclerView.getContext());
		this.recyclerView = recyclerView;
		buttons = new FrameLayout(getContext());
		float density = ResourceUtils.obtainDensity(getContext());
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
			button.setOnClickListener(v -> jump(bottom));
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
		RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
		boolean visible = enabled && contentVisible && recyclerView.isShown() && adapter != null && adapter.getItemCount() > 0
				&& (insets == null || !insets.isVisible(WindowInsets.Type.ime()));
		// Keep the full-size container in layout so inset updates remain available.
		buttons.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
	}

	private void jump(boolean bottom) {
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
		updateVisibility();
	}

	@Override
	protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnGlobalLayoutListener(visibilityListener);
		super.onDetachedFromWindow();
	}
}
