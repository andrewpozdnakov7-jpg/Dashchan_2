package com.mishiranu.dashchan.widget;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Presentation only. PageFragment owns visibility/Back, PostsPage owns every command. */
public final class ThreadFabMenu extends FrameLayout {
	public static final class Action {
		public final int id, title, icon;
		public final boolean enabled;
		public final boolean opensSurface;
		public Action(int id, int title, int icon, boolean enabled, boolean opensSurface) {
			this.id = id; this.title = title; this.icon = icon; this.enabled = enabled; this.opensSurface = opensSurface;
		}
	}
	public interface Host {
		boolean isAvailable();
		List<Action> getActions();
		void onAction(int id);
		void onOpenChanged();
		void onGeometryChanged(Rect bounds);
		View getToolbar();
	}
	private final Host host;
	private final PaddedRecyclerView list;
	private final AppearanceColors colors;
	private final View scrim;
	private final ImageButton fab;
	private final GradientDrawable fabShape = new GradientDrawable();
	private final Drawable moreIcon, closeIcon;
	private final ScrollView scroller;
	private final LinearLayout actions;
	private final Rect bounds = new Rect(), reportedBounds = new Rect();
	private final int[] location = new int[2], rootLocation = new int[2];
	private final IdentityHashMap<View, Integer> accessibility = new IdentityHashMap<>();
	private final IdentityHashMap<ViewGroup, Integer> focusability = new IdentityHashMap<>();
	private final ViewTreeObserver.OnPreDrawListener observer = () -> { syncAvailability(); return true; };
	private boolean enabled, active, available, opened, disposed;
	private boolean scrollToBottomPending;
	private int safeLeft, safeTop, safeRight, safeBottom;
	private ValueAnimator shapeAnimator;
	private float shapeProgress;

	public ThreadFabMenu(PaddedRecyclerView list, Host host) {
		super(list.getContext());
		this.list = list; this.host = host;
		setSaveEnabled(false);
		ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
		colors = AppearanceColors.interfaceCompatibility(theme.base == ThemeEngine.Theme.Base.DARK,
				theme.window, theme.card, theme.accent, ResourceUtils.getColor(getContext(), android.R.attr.textColorPrimary),
				ResourceUtils.getColor(getContext(), R.attr.colorTextError));
		scrim = new View(getContext());
		scrim.setBackgroundColor(android.graphics.Color.BLACK);
		scrim.setAlpha(0f);
		scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		scrim.setOnClickListener(v -> close(true, true));
		addView(scrim);
		actions = new LinearLayout(getContext());
		actions.setOrientation(LinearLayout.VERTICAL);
		actions.setGravity(Gravity.END);
		scroller = new ScrollView(getContext());
		scroller.setFillViewport(false);
		scroller.setClipToPadding(false);
		scroller.addView(actions, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		addView(scroller);
		fab = new ImageButton(getContext());
		moreIcon = getContext().getDrawable(R.drawable.ic_thread_fab_more).mutate();
		closeIcon = getContext().getDrawable(R.drawable.ic_close).mutate();
		fab.setImageDrawable(new android.graphics.drawable.LayerDrawable(new Drawable[] {moreIcon, closeIcon}));
		fab.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.ripple), fabShape, null));
		fab.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
		fab.setPadding(dp(16), dp(16), dp(16), dp(16));
		fab.setElevation(dp(4));
		fab.setOnClickListener(v -> { if (opened) close(true, true); else open(); });
		addView(fab);
		setVisibility(INVISIBLE);
		finishClosed();
		updateFabShape();
	}

	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(this) * value); }
	public boolean isOpen() { return opened; }
	public Rect getFabBounds() { return new Rect(bounds); }
	public void setEnabledForThread(boolean enabled) {
		if (this.enabled != enabled) { this.enabled = enabled; requestLayout(); }
		syncAvailability();
	}
	public void setActive(boolean active) { this.active = active; syncAvailability(); }

	private void syncAvailability() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
		boolean visible = !disposed && enabled && active && hasWindowFocus() && host.isAvailable()
				&& (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime()));
		if (available != visible) {
			available = visible;
			if (!visible) close(false, false);
			setVisibility(visible ? VISIBLE : INVISIBLE);
			fab.setClickable(visible); fab.setFocusable(visible);
			setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		}
		// Insets may change without a size change. Use the owner's padding, subtracting already
		// consumed ancestor insets, and reserve the full scrollbar hit strip throughout scrolling.
		if (updateSafeBounds()) requestLayout();
	}

	private boolean updateSafeBounds() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
		Insets system = insets != null ? insets.getInsets(WindowInsetsCompat.Type.systemBars()
				| WindowInsetsCompat.Type.displayCutout()) : Insets.NONE;
		getLocationInWindow(location); getRootView().getLocationInWindow(rootLocation);
		int left = Math.max(getPaddingLeft(), rootLocation[0] + system.left - location[0]);
		int top = Math.max(getPaddingTop(), rootLocation[1] + system.top - location[1]);
		int right = Math.max(getPaddingRight(), location[0] + getWidth() - rootLocation[0] - getRootView().getWidth() + system.right);
		int bottom = Math.max(getPaddingBottom(), location[1] + getHeight() - rootLocation[1] - getRootView().getHeight() + system.bottom);
		// The track lives at layout-end, just like the FAB (including RTL).
		// The ordinary 16 dp end gap already covers up to 16 dp of this strip.
		int trackExtra = Math.max(0, list.getFastScrollerTouchWidth() - dp(16));
		if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) left = Math.max(left, list.getPaddingLeft() + trackExtra);
		else right = Math.max(right, list.getPaddingRight() + trackExtra);
		boolean changed = left != safeLeft || top != safeTop || right != safeRight || bottom != safeBottom;
		safeLeft = left; safeTop = top; safeRight = right; safeBottom = bottom;
		return changed;
	}

	private void open() {
		syncAvailability();
		if (!available || opened) return;
		scroller.animate().cancel();
		scroller.setAlpha(1f); scroller.setTranslationY(0f);
		actions.removeAllViews();
		List<Action> model = host.getActions();
		// Model order is nearest FAB first. Layout order runs from top to bottom.
		for (int i = model.size() - 1; i >= 0; i--) {
			Action action = model.get(i);
			TextView button = new TextView(getContext());
			button.setText(action.title);
			button.setTextSize(14);
			button.setTextColor(action.enabled ? colors.onPrimaryContainer : colors.disabledContent);
			button.setGravity(Gravity.CENTER_VERTICAL);
			button.setMinHeight(dp(50));
			button.setPadding(dp(18), dp(12), dp(18), dp(12));
			Drawable icon = getContext().getDrawable(action.icon).mutate();
			icon.setTint(action.enabled ? colors.onPrimaryContainer : colors.disabledContent);
			icon.setBounds(0, 0, dp(24), dp(24));
			button.setCompoundDrawablesRelative(icon, null, null, null);
			button.setCompoundDrawablePadding(dp(12));
			button.setBackground(background(colors.primaryContainer, 25));
			button.setEnabled(action.enabled); button.setFocusable(action.enabled);
			button.setAccessibilityDelegate(new AccessibilityDelegate() {
				@Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
					super.onInitializeAccessibilityNodeInfo(view, info); info.setClassName(android.widget.Button.class.getName());
				}
			});
			button.setOnClickListener(v -> {
				if (!opened || !available) return;
				// New surfaces own focus; never schedule a focus restoration behind their window.
				close(false, !action.opensSurface); host.onAction(action.id);
			});
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
			params.gravity = Gravity.END; params.bottomMargin = i == 0 ? 0 : dp(8);
			actions.addView(button, params);
		}
		opened = true;
		scrollToBottomPending = true;
		isolateAccessibility();
		scrim.setVisibility(VISIBLE); scroller.setVisibility(VISIBLE);
		scroller.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
		long duration = InterfaceMotion.duration(230);
		scrim.animate().cancel();
		if (duration > 0) scrim.animate().alpha(.24f).setDuration(duration).start(); else scrim.setAlpha(.24f);
		for (int i = 0; i < actions.getChildCount(); i++) {
			View child = actions.getChildAt(i);
			child.setAlpha(duration > 0 ? 0f : 1f); child.setTranslationY(duration > 0 ? dp(12) : 0f);
			child.setScaleX(duration > 0 ? .96f : 1f); child.setScaleY(duration > 0 ? .96f : 1f);
			if (duration > 0) child.animate().alpha(1f).translationY(0).scaleX(1).scaleY(1)
					.setStartDelay((actions.getChildCount() - 1 - i) * 18L).setDuration(duration)
					.setInterpolator(InterfaceMotion.STANDARD).start();
		}
		animateShape(true, duration);
		fab.setContentDescription(getContext().getString(R.string.thread_fab_close));
		host.onOpenChanged();
		requestLayout();
		// Do not replace the accessibility tree again while opening.
		fab.requestFocus();
	}

	public boolean close(boolean animate, boolean restoreFocus) {
		if (!opened) {
			if (!animate) {
				scrim.animate().cancel(); scroller.animate().cancel();
				for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).animate().cancel();
				finishClosed(); animateShape(false, 0);
			}
			return false;
		}
		opened = false;
		restoreAccessibility();
		scroller.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		for (int i = 0; i < actions.getChildCount(); i++) {
			View child = actions.getChildAt(i); child.animate().cancel();
			child.setClickable(false); child.setFocusable(false); child.cancelPendingInputEvents();
		}
		long duration = animate ? InterfaceMotion.duration(180) : 0;
		scrim.animate().cancel(); scroller.animate().cancel();
		if (duration > 0) {
			scrim.animate().alpha(0).setDuration(duration).start();
			scroller.animate().alpha(0).translationY(dp(8)).setDuration(duration)
					.withEndAction(() -> { if (!opened) finishClosed(); }).start();
		} else finishClosed();
		animateShape(false, duration);
		fab.setContentDescription(getContext().getString(R.string.thread_fab_actions));
		host.onOpenChanged();
		if (restoreFocus && available) {
			fab.requestFocus();
			fab.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
		}
		return true;
	}
	private void finishClosed() {
		scrollToBottomPending = false;
		scrim.setVisibility(INVISIBLE); scroller.setVisibility(INVISIBLE);
		scrim.setAlpha(0); scroller.setAlpha(1); scroller.setTranslationY(0);
		fab.setContentDescription(getContext().getString(R.string.thread_fab_actions));
	}
	private Drawable background(int color, float radius) {
		GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius));
		return new RippleDrawable(ColorStateList.valueOf(colors.ripple), shape, null);
	}
	private void updateFabShape() {
		fabShape.setColor(androidx.core.graphics.ColorUtils.blendARGB(colors.primaryContainer, colors.primary, shapeProgress));
		fabShape.setCornerRadius(dp(18 + 10 * shapeProgress));
		int tint = androidx.core.graphics.ColorUtils.blendARGB(colors.onPrimaryContainer, colors.onPrimary, shapeProgress);
		moreIcon.setTint(tint); closeIcon.setTint(tint);
		moreIcon.setAlpha(Math.round(255 * (1 - shapeProgress))); closeIcon.setAlpha(Math.round(255 * shapeProgress));
	}
	private void animateShape(boolean open, long duration) {
		if (shapeAnimator != null) { shapeAnimator.cancel(); shapeAnimator = null; }
		if (duration == 0) { shapeProgress = open ? 1 : 0; updateFabShape(); return; }
		shapeAnimator = ValueAnimator.ofFloat(shapeProgress, open ? 1 : 0);
		shapeAnimator.setDuration(duration); shapeAnimator.setInterpolator(InterfaceMotion.STANDARD);
		shapeAnimator.addUpdateListener(a -> { shapeProgress = (float) a.getAnimatedValue(); updateFabShape(); });
		shapeAnimator.start();
	}

	private void isolateAccessibility() {
		if (getParent() instanceof ViewGroup) {
			ViewGroup parent = (ViewGroup) getParent();
			for (int i = 0; i < parent.getChildCount(); i++) if (parent.getChildAt(i) != this) hideAccessibility(parent.getChildAt(i));
		}
		hideAccessibility(host.getToolbar());
	}
	private void hideAccessibility(View view) {
		if (view != null && !accessibility.containsKey(view)) {
			accessibility.put(view, view.getImportantForAccessibility());
			view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				focusability.put(group, group.getDescendantFocusability());
				group.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
			}
		}
	}
	private void restoreAccessibility() {
		for (Map.Entry<View, Integer> entry : accessibility.entrySet()) {
			if (entry.getKey().getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
				entry.getKey().setImportantForAccessibility(entry.getValue());
		}
		accessibility.clear();
		for (Map.Entry<ViewGroup, Integer> entry : focusability.entrySet()) {
			if (entry.getKey().getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS)
				entry.getKey().setDescendantFocusability(entry.getValue());
		}
		focusability.clear();
	}

	@Override protected void onMeasure(int widthSpec, int heightSpec) {
		int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
		setMeasuredDimension(width, height); updateSafeBounds();
		scrim.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
		fab.measure(MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
		scroller.measure(MeasureSpec.makeMeasureSpec(Math.max(0, Math.min(dp(360), width - safeLeft - safeRight - dp(32))), MeasureSpec.AT_MOST),
				MeasureSpec.makeMeasureSpec(Math.max(0, height - safeTop - safeBottom - dp(84)), MeasureSpec.AT_MOST));
	}
	@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int width = r - l, height = b - t;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int x = rtl ? safeLeft + dp(16) : width - safeRight - dp(72), y = height - safeBottom - dp(64);
		fab.layout(x, y, x + dp(56), y + dp(56));
		scrim.layout(0, 0, width, height);
		int actionX = rtl ? x : x + dp(56) - scroller.getMeasuredWidth();
		int actionBottom = y - dp(12);
		scroller.layout(actionX, actionBottom - scroller.getMeasuredHeight(), actionX + scroller.getMeasuredWidth(), actionBottom);
		if (opened && scrollToBottomPending) {
			scrollToBottomPending = false;
			scroller.scrollTo(0, actions.getHeight());
		}
		bounds.set(x, y, x + dp(56), y + dp(56));
		if (!reportedBounds.equals(bounds)) { reportedBounds.set(bounds); host.onGeometryChanged(new Rect(bounds)); }
	}
	@Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); syncAvailability(); }
	@Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(observer); }
	@Override protected void onDetachedFromWindow() {
		getViewTreeObserver().removeOnPreDrawListener(observer); close(false, false);
		scrim.animate().cancel(); scroller.animate().cancel();
		if (shapeAnimator != null) { shapeAnimator.cancel(); shapeAnimator = null; }
		super.onDetachedFromWindow();
	}
	public void dispose() { disposed = true; active = false; syncAvailability(); restoreAccessibility(); }
}
