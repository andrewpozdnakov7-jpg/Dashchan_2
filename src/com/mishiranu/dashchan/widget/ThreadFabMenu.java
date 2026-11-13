package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.OpaqueColors;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Presentation only. PageFragment owns visibility/Back, PostsPage owns every command. */
public final class ThreadFabMenu extends FrameLayout {
	private static final int TOUCH_SIZE = 48, SURFACE_SIZE = 40;
	private static final float HIDE_TRAVEL_DP = 36f, SHOW_TRAVEL_DP = 18f;
	public static final class Action {
		public final int id, title, icon;
		public final boolean enabled;
		public final boolean opensSurface;
		public final boolean selected;
		public Action(int id, int title, int icon, boolean enabled, boolean opensSurface) {
			this(id, title, icon, enabled, opensSurface, false);
		}
		public Action(int id, int title, int icon, boolean enabled, boolean opensSurface, boolean selected) {
			this.id = id; this.title = title; this.icon = icon; this.enabled = enabled;
			this.opensSurface = opensSurface; this.selected = selected;
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
	private final int panelWidth, actionTouchSize, panelGap, panelOpenTravel, panelCloseTravel;
	private final Rect bounds = new Rect(), reportedBounds = new Rect();
	private final int[] location = new int[2], rootLocation = new int[2];
	private final IdentityHashMap<View, Integer> accessibility = new IdentityHashMap<>();
	private final IdentityHashMap<ViewGroup, Integer> focusability = new IdentityHashMap<>();
	private final ViewTreeObserver.OnPreDrawListener observer = () -> { syncAvailability(); return true; };
	private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
		@Override public void onScrolled(RecyclerView view, int dx, int dy) {
			if (!available || opened || touchExplorationEnabled()) return;
			if (!view.canScrollVertically(-1)) {
				scrollTravel = 0f;
				setScrollHidden(false);
				return;
			}
			// Layout, refresh and restored anchors must not count as a user's scroll.
			if (dy == 0 || view.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) return;
			float delta = dy / ResourceUtils.obtainDensity(view);
			if (delta * scrollTravel < 0f) scrollTravel = 0f;
			scrollTravel += delta;
			if (scrollTravel > HIDE_TRAVEL_DP) setScrollHidden(true);
			else if (scrollTravel < -SHOW_TRAVEL_DP) setScrollHidden(false);
		}
	};
	private boolean enabled, active, available, opened, disposed;
	private boolean scrollHidden, fabShown;
	private float scrollTravel, visibilityProgress;
	private ValueAnimator visibilityAnimator;
	private boolean scrollToBottomPending;
	private int safeLeft, safeTop, safeRight, safeBottom;
	private ValueAnimator shapeAnimator;
	private ValueAnimator menuAnimator;
	private float shapeProgress;

	public ThreadFabMenu(PaddedRecyclerView list, Host host) {
		super(list.getContext());
		this.list = list; this.host = host;
		setSaveEnabled(false);
		ThemeEngine.Theme theme = ThemeEngine.getTheme(getContext());
		colors = AppearanceColors.interfaceCompatibility(theme.base == ThemeEngine.Theme.Base.DARK,
				theme.window, theme.card, theme.accent, ResourceUtils.getColor(getContext(), android.R.attr.textColorPrimary),
				ResourceUtils.getColor(getContext(), R.attr.colorTextError));
		panelWidth = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_panel_width);
		actionTouchSize = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_action_touch_size);
		panelGap = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_visible_gap);
		panelOpenTravel = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_open_translation);
		panelCloseTravel = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_close_translation);
		scrim = new View(getContext());
		scrim.setBackgroundColor(android.graphics.Color.BLACK);
		scrim.setAlpha(0f);
		scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		scrim.setOnClickListener(v -> close(true, true));
		addView(scrim);
		actions = new LinearLayout(getContext());
		actions.setOrientation(LinearLayout.VERTICAL);
		actions.setGravity(Gravity.CENTER_HORIZONTAL);
		int horizontalPadding = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_padding_horizontal);
		int verticalPadding = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_padding_vertical);
		actions.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
		scroller = new ScrollView(getContext());
		scroller.setFillViewport(false);
		scroller.setVerticalScrollBarEnabled(false);
		GradientDrawable panelShape = new GradientDrawable();
		panelShape.setColor(colors.surfaceContainer);
		panelShape.setCornerRadius(getResources().getDimension(R.dimen.thread_fab_compact_corner_radius));
		scroller.setBackground(panelShape);
		scroller.setClipToOutline(true);
		scroller.setElevation(getResources().getDimension(R.dimen.thread_fab_compact_elevation));
		// A fading, already closed panel must not scroll or pass a touch through to a post.
		scroller.setOnTouchListener((view, event) -> !opened);
		scroller.addView(actions, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		addView(scroller);
		fab = new ImageButton(getContext());
		moreIcon = getContext().getDrawable(R.drawable.ic_thread_fab_more).mutate();
		closeIcon = getContext().getDrawable(R.drawable.ic_close).mutate();
		fab.setImageDrawable(new android.graphics.drawable.LayerDrawable(new Drawable[] {moreIcon, closeIcon}));
		InsetDrawable surface = new InsetDrawable(fabShape, dp((TOUCH_SIZE - SURFACE_SIZE) / 2f));
		fab.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.ripple), surface, null));
		fab.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
		int iconPadding = dp((TOUCH_SIZE - 21) / 2f);
		fab.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
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
	private boolean touchExplorationEnabled() {
		AccessibilityManager manager = (AccessibilityManager) getContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
		return manager != null && manager.isTouchExplorationEnabled();
	}
	private void setScrollHidden(boolean hidden) {
		if (scrollHidden == hidden) return;
		scrollHidden = hidden;
		updateFabVisibility(true);
	}
	private void cancelVisibilityAnimation() {
		ValueAnimator animator = visibilityAnimator;
		visibilityAnimator = null;
		if (animator != null) animator.cancel();
	}
	private void applyVisibilityProgress(float progress) {
		visibilityProgress = progress;
		float clamped = Math.max(0f, Math.min(1f, progress));
		fab.setAlpha(clamped);
		float scale = .94f + .06f * clamped;
		fab.setScaleX(scale); fab.setScaleY(scale);
		fab.setTranslationY((1f - clamped) * dp(TOUCH_SIZE) * .8f - Math.max(0f, progress - 1f) * dp(10));
	}
	private void updateFabVisibility(boolean animate) {
		boolean show = available && (!scrollHidden || opened);
		boolean changed = fabShown != show;
		fabShown = show;
		fab.setClickable(show); fab.setFocusable(show);
		if (!show) { fab.cancelPendingInputEvents(); fab.setPressed(false); }
		setImportantForAccessibility(show ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		if (!changed && animate) return;
		cancelVisibilityAnimation();
		float target = show ? 1f : 0f;
		long duration = animate && getWidth() > 0 ? InterfaceMotion.duration(show ? 420 : 260) : 0;
		if (duration == 0) {
			applyVisibilityProgress(target);
			fab.setVisibility(show ? VISIBLE : INVISIBLE);
			return;
		}
		fab.setVisibility(VISIBLE);
		ValueAnimator animator = ValueAnimator.ofFloat(visibilityProgress, target);
		visibilityAnimator = animator;
		animator.setDuration(duration);
		if (show) {
			// Damped return like Artefact; alpha/scale are clamped, only position overshoots.
			animator.setInterpolator(t -> t == 1f ? 1f : (float) (1 - Math.exp(-7 * t)
					* (Math.cos(10 * t) + .7 * Math.sin(10 * t))));
		} else animator.setInterpolator(InterfaceMotion.STANDARD);
		animator.addUpdateListener(a -> applyVisibilityProgress((float) a.getAnimatedValue()));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (visibilityAnimator != animation) return;
				visibilityAnimator = null;
				applyVisibilityProgress(target);
				fab.setVisibility(fabShown ? VISIBLE : INVISIBLE);
			}
		});
		animator.start();
	}

	private void syncAvailability() {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
		boolean visible = !disposed && enabled && active && hasWindowFocus() && host.isAvailable()
				&& (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime()));
		if (available != visible) {
			available = visible;
			scrollHidden = false; scrollTravel = 0f;
			if (!visible) close(false, false);
			setVisibility(visible ? VISIBLE : INVISIBLE);
			// Obscured screens and paused owners disappear immediately; never leave a ghost above them.
			updateFabVisibility(visible);
		}
		if (available && scrollHidden && touchExplorationEnabled()) setScrollHidden(false);
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
		if (!available || !fabShown || opened) return;
		scrollHidden = false; scrollTravel = 0f;
		updateFabVisibility(false);
		boolean continuing = scroller.getVisibility() == VISIBLE;
		cancelMenuAnimation();
		actions.removeAllViews();
		List<Action> model = host.getActions();
		if (model.isEmpty()) { finishClosed(); animateShape(false, 0); return; }
		// Model order is nearest FAB first. Layout order runs from top to bottom.
		for (int i = model.size() - 1; i >= 0; i--) {
			Action action = model.get(i);
			ImageButton button = new ImageButton(getContext());
			String title = getContext().getString(action.title);
			button.setContentDescription(title);
			button.setTooltipText(title);
			button.setSelected(action.selected);
			button.setMinimumWidth(0); button.setMinimumHeight(0);
			int iconSize = getResources().getDimensionPixelSize(R.dimen.thread_fab_compact_icon_size);
			int iconPadding = (actionTouchSize - iconSize) / 2;
			button.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
			button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
			Drawable icon = getContext().getDrawable(action.icon).mutate();
			int tint = OpaqueColors.readable(action.selected ? colors.primary : colors.onSurface,
					colors.surfaceContainer, 4.5, OpaqueColors.Policy.INTERFACE_COMPATIBILITY);
			icon.setTint(action.enabled ? tint : colors.disabledContent);
			button.setImageDrawable(icon);
			button.setBackground(actionBackground());
			button.setEnabled(action.enabled); button.setFocusable(action.enabled);
			button.setAccessibilityDelegate(new AccessibilityDelegate() {
				@Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
					super.onInitializeAccessibilityNodeInfo(view, info); info.setClassName(android.widget.Button.class.getName());
				}
			});
			button.setOnClickListener(v -> {
				syncAvailability();
				if (!opened || !available) return;
				// A background refresh may change availability while the panel is open.
				for (Action current : host.getActions()) if (current.id == action.id && current.enabled) {
					// New surfaces own focus; never restore focus behind their window.
					close(false, !current.opensSurface); host.onAction(current.id);
					return;
				}
			});
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(actionTouchSize, actionTouchSize);
			actions.addView(button, params);
		}
		opened = true;
		scrollToBottomPending = true;
		isolateAccessibility();
		scrim.setVisibility(VISIBLE); scroller.setVisibility(VISIBLE);
		scroller.setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
		scroller.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
		long duration = InterfaceMotion.duration(230);
		if (!continuing) {
			scroller.setAlpha(0f); scroller.setTranslationY(panelOpenTravel);
			scroller.setScaleX(.96f); scroller.setScaleY(.96f); scrim.setAlpha(0f);
		}
		animateMenu(true, duration);
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
				cancelMenuAnimation();
				finishClosed(); animateShape(false, 0);
			}
			return false;
		}
		opened = false;
		restoreAccessibility();
		scroller.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		scroller.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
		scroller.cancelPendingInputEvents();
		for (int i = 0; i < actions.getChildCount(); i++) {
			View child = actions.getChildAt(i);
			child.setEnabled(false); child.setClickable(false); child.setFocusable(false);
			child.setTooltipText(null); child.setLongClickable(false);
			child.cancelPendingInputEvents(); child.setPressed(false);
		}
		long duration = animate ? InterfaceMotion.duration(180) : 0;
		animateMenu(false, duration);
		animateShape(false, duration);
		fab.setContentDescription(getContext().getString(R.string.thread_fab_actions));
		host.onOpenChanged();
		if (restoreFocus && available) {
			fab.requestFocus();
			fab.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
		}
		return true;
	}
	private void cancelMenuAnimation() {
		ValueAnimator animator = menuAnimator;
		menuAnimator = null;
		if (animator != null) animator.cancel();
	}
	private void animateMenu(boolean open, long duration) {
		cancelMenuAnimation();
		if (duration == 0) {
			if (open) {
				scroller.setAlpha(1f); scroller.setTranslationY(0f);
				scroller.setScaleX(1f); scroller.setScaleY(1f); scrim.setAlpha(.10f);
			} else finishClosed();
			return;
		}
		float alpha = scroller.getAlpha(), translation = scroller.getTranslationY();
		float scale = scroller.getScaleX(), scrimAlpha = scrim.getAlpha();
		float targetAlpha = open ? 1f : 0f, targetTranslation = open ? 0f : panelCloseTravel;
		float targetScale = open ? 1f : .96f, targetScrimAlpha = open ? .10f : 0f;
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		menuAnimator = animator;
		animator.setDuration(duration); animator.setInterpolator(InterfaceMotion.STANDARD);
		animator.addUpdateListener(a -> {
			if (menuAnimator != a) return;
			float f = (float) a.getAnimatedValue();
			scroller.setAlpha(alpha + (targetAlpha - alpha) * f);
			scroller.setTranslationY(translation + (targetTranslation - translation) * f);
			float currentScale = scale + (targetScale - scale) * f;
			scroller.setScaleX(currentScale); scroller.setScaleY(currentScale);
			scrim.setAlpha(scrimAlpha + (targetScrimAlpha - scrimAlpha) * f);
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (menuAnimator != animation) return;
				menuAnimator = null;
				if (!opened) finishClosed();
			}
		});
		animator.start();
	}
	private void finishClosed() {
		scrollToBottomPending = false;
		scrim.setVisibility(INVISIBLE); scroller.setVisibility(INVISIBLE);
		scrim.setAlpha(0); scroller.setAlpha(0); scroller.setTranslationY(panelOpenTravel);
		scroller.setScaleX(.96f); scroller.setScaleY(.96f);
		scroller.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		scroller.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
		fab.setContentDescription(getContext().getString(R.string.thread_fab_actions));
	}
	private Drawable actionBackground() {
		GradientDrawable mask = new GradientDrawable();
		mask.setShape(GradientDrawable.OVAL); mask.setColor(android.graphics.Color.WHITE);
		return new RippleDrawable(ColorStateList.valueOf(colors.ripple), null, mask);
	}
	private void updateFabShape() {
		fabShape.setColor(androidx.core.graphics.ColorUtils.blendARGB(colors.primaryContainer, colors.primary, shapeProgress));
		fabShape.setCornerRadius(dp(14 + 6 * shapeProgress));
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
		fab.measure(MeasureSpec.makeMeasureSpec(dp(TOUCH_SIZE), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(TOUCH_SIZE), MeasureSpec.EXACTLY));
		int actionBottom = height - safeBottom - dp(8 + TOUCH_SIZE) + dp((TOUCH_SIZE - SURFACE_SIZE) / 2f) - panelGap;
		scroller.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
				MeasureSpec.makeMeasureSpec(Math.max(0, actionBottom - safeTop), MeasureSpec.AT_MOST));
	}
	@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int width = r - l, height = b - t;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int x = rtl ? safeLeft + dp(16) : width - safeRight - dp(16 + TOUCH_SIZE), y = height - safeBottom - dp(8 + TOUCH_SIZE);
		// Keep the shared axis inside both system safe bounds and the complete scrollbar hit strip.
		int panelSafeLeft = rtl ? Math.max(safeLeft, list.getPaddingLeft() + list.getFastScrollerTouchWidth()) : safeLeft;
		int panelSafeRight = rtl ? safeRight : Math.max(safeRight, list.getPaddingRight() + list.getFastScrollerTouchWidth());
		int panelExtra = (panelWidth - dp(TOUCH_SIZE) + 1) / 2;
		int minX = panelSafeLeft + panelExtra, maxX = width - panelSafeRight - dp(TOUCH_SIZE) - panelExtra;
		x = Math.max(minX, Math.min(x, maxX));
		fab.layout(x, y, x + dp(TOUCH_SIZE), y + dp(TOUCH_SIZE));
		fab.setPivotX(dp(TOUCH_SIZE) / 2f); fab.setPivotY(dp(TOUCH_SIZE));
		scrim.layout(0, 0, width, height);
		int actionX = x + (dp(TOUCH_SIZE) - scroller.getMeasuredWidth()) / 2;
		// Visible FAB surface begins inside its 48 dp touch target; do not count this inset twice.
		int actionBottom = y + dp((TOUCH_SIZE - SURFACE_SIZE) / 2f) - panelGap;
		scroller.layout(actionX, actionBottom - scroller.getMeasuredHeight(), actionX + scroller.getMeasuredWidth(), actionBottom);
		scroller.setPivotX(scroller.getWidth() / 2f); scroller.setPivotY(scroller.getHeight());
		if (opened && scrollToBottomPending) {
			scrollToBottomPending = false;
			scroller.scrollTo(0, actions.getHeight());
		}
		bounds.set(x, y, x + dp(TOUCH_SIZE), y + dp(TOUCH_SIZE));
		if (!reportedBounds.equals(bounds)) { reportedBounds.set(bounds); host.onGeometryChanged(new Rect(bounds)); }
	}
	@Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); syncAvailability(); }
	@Override protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnPreDrawListener(observer);
		list.addOnScrollListener(scrollListener);
	}
	@Override protected void onDetachedFromWindow() {
		list.removeOnScrollListener(scrollListener);
		cancelVisibilityAnimation();
		scrollHidden = false; scrollTravel = 0f;
		fabShown = false; available = false;
		applyVisibilityProgress(0f); fab.setVisibility(INVISIBLE); setVisibility(INVISIBLE);
		getViewTreeObserver().removeOnPreDrawListener(observer); close(false, false);
		cancelMenuAnimation();
		if (shapeAnimator != null) { shapeAnimator.cancel(); shapeAnimator = null; }
		super.onDetachedFromWindow();
	}
	public void dispose() {
		disposed = true; active = false;
		list.removeOnScrollListener(scrollListener);
		syncAvailability(); close(false, false); cancelVisibilityAnimation(); restoreAccessibility();
	}
}
