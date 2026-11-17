package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.transition.Fade;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.ImageLoader;
import com.mishiranu.dashchan.content.security.SecurePopupContext;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** One non-dimming, focusable status explanation. Status rows do not perform actions. */
public final class StatusExplanationPopup {
	public static final class Entry {
		final String title;
		final int iconAttribute;
		final Uri uri;
		public Entry(String title, int iconAttribute, Uri uri) {
			this.title = title; this.iconAttribute = iconAttribute; this.uri = uri;
		}
	}
	private static WeakReference<StatusExplanationPopup> active = new WeakReference<>(null);
	private final View anchor, owner;
	private final PopupWindow window;
	private final ScrollView scroll;
	private final ArrayList<ImageView> images = new ArrayList<>();
	private final Rect originalAnchor = new Rect(), originalViewport = new Rect();
	private ViewTreeObserver tree;
	private Lifecycle lifecycle;
	private boolean released;
	private final ViewTreeObserver.OnScrollChangedListener onScroll = this::dismissImmediately;
	private final ViewTreeObserver.OnGlobalLayoutListener onLayout = this::checkAnchor;
	private final LifecycleEventObserver onLifecycle = (source, event) -> {
		if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) dismissImmediately();
	};
	private final View.OnAttachStateChangeListener onAttach = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { dismissImmediately(); }
	};

	private StatusExplanationPopup(View anchor, View owner, String chanName, List<Entry> entries, String email) {
		this.anchor = anchor; this.owner = owner;
		Context context = SecurePopupContext.wrap(anchor.getContext());
		AppearanceColors colors = StandardAppearance.colors(context);
		scroll = new ScrollView(context);
		scroll.setLayoutDirection(anchor.getLayoutDirection());
		scroll.setFillViewport(false);
		scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
		scroll.setAccessibilityPaneTitle(context.getString(R.string.compact_post_statuses));
		LinearLayout body = new LinearLayout(context);
		body.setOrientation(LinearLayout.VERTICAL);
		body.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
		scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
		for (Entry entry : entries) {
			LinearLayout row = new LinearLayout(context);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setMinimumHeight(dp(40)); row.setPadding(0, dp(8), 0, dp(8));
			ImageView icon = new ImageView(context);
			icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
			LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
			iconParams.setMarginEnd(dp(12)); row.addView(icon, iconParams);
			images.add(icon);
			if (entry.uri != null) ImageLoader.getInstance().loadImage(Chan.get(chanName), entry.uri, false, icon);
			else if (entry.iconAttribute != 0) {
				icon.setImageResource(ResourceUtils.getResourceId(context, entry.iconAttribute, 0));
				icon.setImageTintList(ColorStateList.valueOf(InterfaceAppearance.contrasting(colors.onSurfaceVariant, colors.surfaceContainer, 3)));
			}
			TextView text = new TextView(context);
			AppearanceTypography.apply(text, AppearanceTypography.Role.BODY_MEDIUM);
			text.setTextColor(InterfaceAppearance.contrasting(colors.onSurface, colors.surfaceContainer, 4.5));
			text.setText(entry.title); text.setSingleLine(false); text.setEllipsize(null);
			row.addView(text, new LinearLayout.LayoutParams(-2, -2));
			body.addView(row, new LinearLayout.LayoutParams(-1, -2));
		}
		if (!StringUtils.isEmpty(email)) {
			Button copy = new Button(context, null, android.R.attr.borderlessButtonStyle);
			AppearanceTypography.apply(copy, AppearanceTypography.Role.LABEL_LARGE);
			copy.setText(R.string.copy_email); copy.setMinHeight(dp(48));
			copy.setTextColor(InterfaceAppearance.contrasting(colors.primary, colors.surfaceContainer, 4.5));
			copy.setOnClickListener(view -> { StringUtils.copyToClipboard(context, email); dismiss(); });
			body.addView(copy, new LinearLayout.LayoutParams(-1, -2));
		}
		window = new PopupWindow(scroll, 0, 0, true);
		GradientDrawable background = new GradientDrawable();
		background.setColor(colors.surfaceContainer); background.setCornerRadius(dp(12));
		window.setBackgroundDrawable(background); window.setElevation(dp(6));
		window.setOutsideTouchable(true); window.setClippingEnabled(true);
		window.setTouchModal(true); window.setAnimationStyle(0);
		window.setIsLaidOutInScreen(true); window.setIsClippedToScreen(true);
		window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
				| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
		// One animation owner: do not combine a window animation and ViewPropertyAnimator.
		Fade enter = new Fade(); enter.setDuration(InterfaceMotion.isEnabled() ? InterfaceMotion.duration(180) : 0);
		enter.setInterpolator(InterfaceMotion.STANDARD); window.setEnterTransition(enter);
		Fade exit = new Fade(); exit.setDuration(InterfaceMotion.isEnabled() ? InterfaceMotion.duration(100) : 0);
		exit.setInterpolator(InterfaceMotion.STANDARD); window.setExitTransition(exit);
		window.setOnDismissListener(this::release);
		scroll.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
			}
			@Override public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
				if (action == AccessibilityNodeInfo.ACTION_DISMISS) { dismiss(); return true; }
				return super.performAccessibilityAction(host, action, args);
			}
		});
	}

	public static boolean show(View anchor, View owner, String chanName, List<Entry> entries, String email) {
		if (!CompactDialogUi.isEnabled() || anchor == null || owner == null || entries.isEmpty()
				|| !anchor.isAttachedToWindow() || !owner.isAttachedToWindow() || !anchor.isShown()) return false;
		StatusExplanationPopup previous = active.get();
		if (previous != null) previous.dismissImmediately();
		StatusExplanationPopup popup = new StatusExplanationPopup(anchor, owner, chanName, entries, email);
		if (!popup.showInternal() || popup.released || !popup.window.isShowing()) return false;
		active = new WeakReference<>(popup); return true;
	}
	/** A recycled holder may receive new content without moving or detaching. */
	public static void dismissForOwner(View owner) {
		StatusExplanationPopup popup = active.get();
		if (popup != null && popup.owner == owner) {
			popup.dismissImmediately();
		}
	}
	private int dp(int value) { return Math.round(value * ResourceUtils.obtainDensity(anchor)); }
	private static Lifecycle hostLifecycle(View anchor) {
		Context host = anchor.getContext();
		while (!(host instanceof LifecycleOwner) && host instanceof ContextWrapper) {
			Context next = ((ContextWrapper) host).getBaseContext(); if (next == host) break; host = next;
		}
		return host instanceof LifecycleOwner ? ((LifecycleOwner) host).getLifecycle() : null;
	}
	public static boolean isHostActive(View anchor) {
		Lifecycle lifecycle = hostLifecycle(anchor);
		return lifecycle != null && lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
	}
	private static Rect screenBounds(View view) {
		int[] location = new int[2]; view.getLocationOnScreen(location);
		return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
	}
	/** Pure geometry: arguments and result are all in screen coordinates. */
	static Rect placement(Rect viewport, Rect anchor, int width, int height, int margin, int gap, boolean rtl) {
		Rect safe = new Rect(viewport); safe.inset(margin, margin);
		if (safe.width() <= 0 || safe.height() <= 0 || !Rect.intersects(viewport, anchor)) return null;
		width = Math.min(width, safe.width());
		int below = Math.max(0, safe.bottom - anchor.bottom - gap);
		int above = Math.max(0, anchor.top - safe.top - gap);
		boolean useBelow = below >= height || below >= above;
		height = Math.min(height, Math.min(safe.height(), useBelow ? below : above));
		if (width <= 0 || height <= 0) return null;
		int x = rtl ? anchor.right - width : anchor.left;
		x = Math.max(safe.left, Math.min(x, safe.right - width));
		int y = useBelow ? anchor.bottom + gap : anchor.top - gap - height;
		y = Math.max(safe.top, Math.min(y, safe.bottom - height));
		return new Rect(x, y, x + width, y + height);
	}
	private boolean showInternal() {
		anchor.getWindowVisibleDisplayFrame(originalViewport); originalAnchor.set(screenBounds(anchor));
		int width = Math.min(dp(320), originalViewport.width() - dp(32));
		if (width <= 0) { release(); return false; }
		scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		Rect bounds = placement(originalViewport, originalAnchor, scroll.getMeasuredWidth(),
				scroll.getMeasuredHeight(), dp(16), dp(8), anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
		if (bounds == null || bounds.height() < dp(56)) { release(); return false; }
		window.setWidth(bounds.width()); window.setHeight(bounds.height());
		anchor.addOnAttachStateChangeListener(onAttach); owner.addOnAttachStateChangeListener(onAttach);
		tree = owner.getViewTreeObserver(); tree.addOnScrollChangedListener(onScroll); tree.addOnGlobalLayoutListener(onLayout);
		lifecycle = hostLifecycle(anchor);
		if (lifecycle == null || !lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) { release(); return false; }
		lifecycle.addObserver(onLifecycle);
		try {
			// Avoid showAsDropDown(), which can move a scrolling ancestor to expose its anchor.
			window.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, bounds.left, bounds.top);
			return true;
		} catch (WindowManager.BadTokenException | IllegalStateException e) { release(); return false; }
	}
	private void checkAnchor() {
		Rect viewport = new Rect(); anchor.getWindowVisibleDisplayFrame(viewport);
		if (!anchor.isShown() || !owner.isShown() || !viewport.equals(originalViewport)
				|| !screenBounds(anchor).equals(originalAnchor)) dismissImmediately();
	}
	private void dismissImmediately() { window.setExitTransition(null); dismiss(); }
	public void dismiss() { if (window.isShowing()) window.dismiss(); else release(); }
	private void release() {
		if (released) return; released = true;
		if (tree != null && tree.isAlive()) { tree.removeOnGlobalLayoutListener(onLayout); tree.removeOnScrollChangedListener(onScroll); }
		anchor.removeOnAttachStateChangeListener(onAttach); owner.removeOnAttachStateChangeListener(onAttach);
		if (lifecycle != null) lifecycle.removeObserver(onLifecycle);
		for (ImageView image : images) ImageLoader.getInstance().cancel(image);
		if (active.get() == this) active.clear();
	}
}
