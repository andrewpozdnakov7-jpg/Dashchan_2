package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.transition.Fade;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.posting.text.MarkupButtonProvider;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Presentation-only glyph proxies. Original buttons stay in their owner and retain their callbacks. */
final class PostingFormattingMenu {
	private final View anchor, panel;
	private final PopupWindow window;
	private final List<View> commands;
	private final Map<View, Integer> tags;
	private final int text, accent;
	private final Consumer<View> select;
	private final ScrollView scroll;
	private Runnable onDismiss;
	private boolean released, selected;
	private ViewTreeObserver observer;
	private final Rect initialViewport = new Rect(), initialPanel = new Rect();
	private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onLayoutChanged;
	private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::dismiss;
	private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { dismiss(); }
	};

	PostingFormattingMenu(View anchor, View panel, List<View> commands, Map<View, Integer> tags,
			int tonal, int text, int accent, Consumer<View> select) {
		this.anchor = anchor; this.panel = panel; this.commands = commands; this.tags = tags;
		this.text = text; this.accent = accent; this.select = select;
		Context context = com.mishiranu.dashchan.content.security.SecurePopupContext.wrap(anchor.getContext());
		scroll = new ScrollView(context);
		scroll.setPadding(dp(8), dp(8), dp(8), dp(8));
		scroll.setClipToPadding(true); scroll.setVerticalScrollBarEnabled(false);
		scroll.setAccessibilityPaneTitle(context.getString(R.string.posting_format_more));
		window = new PopupWindow(scroll, 0, 0, true);
		GradientDrawable background = new GradientDrawable();
		// Same tonal surface and accent family as the editor's formatting capsule.
		background.setColor(tonal);
		background.setCornerRadius(dp(18));
		background.setStroke(dp(1), accent);
		window.setBackgroundDrawable(background); window.setElevation(dp(8));
		window.setOutsideTouchable(true); window.setClippingEnabled(true);
		window.setIsLaidOutInScreen(true); window.setIsClippedToScreen(true);
		// Keep normal popup focus/Back handling, but never become the IME's input target.
		// Focusable + NOT_NEEDED uses FLAG_ALT_FOCUSABLE_IM; the editor keeps the IME.
		window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
				| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
		Fade enter = new Fade(); enter.setDuration(InterfaceMotion.duration(160));
		enter.setInterpolator(InterfaceMotion.STANDARD); window.setEnterTransition(enter);
		Fade exit = new Fade(); exit.setDuration(InterfaceMotion.duration(100));
		exit.setInterpolator(InterfaceMotion.STANDARD); window.setExitTransition(exit);
		window.setOnDismissListener(this::release);
	}

	private int dp(float value) { return Math.round(ResourceUtils.obtainDensity(anchor) * value); }

	private void onLayoutChanged() {
		Rect viewport = new Rect(); anchor.getWindowVisibleDisplayFrame(viewport);
		if (!viewport.equals(initialViewport) || !screenBounds(panel).equals(initialPanel)) dismiss();
	}

	void setOnDismissListener(Runnable listener) { onDismiss = listener; }

	void show() {
		if (released) return;
		if (!anchor.isAttachedToWindow() || !anchor.isShown() || anchor.getWindowToken() == null
				|| !panel.isAttachedToWindow() || !panel.isShown()) { release(); return; }
		anchor.getWindowVisibleDisplayFrame(initialViewport);
		initialPanel.set(screenBounds(panel));
		int cell = dp(44);
		for (View original : commands) if (original instanceof TextView) {
			TextView label = (TextView) original;
			cell = Math.max(cell, (int) Math.ceil(android.text.Layout.getDesiredWidth(label.getText(), label.getPaint())) + dp(8));
			cell = Math.max(cell, (int) Math.ceil(label.getPaint().getFontSpacing()) + dp(8));
		}
		Rect bounds = calculateBounds(initialViewport, initialPanel, screenBounds(anchor),
				ResourceUtils.obtainDensity(anchor), commands.size(), anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL, cell);
		if (bounds == null) { release(); return; }
		int columns = Math.max(1, (bounds.width() - dp(8) * 2) / cell);
		LinearLayout grid = new LinearLayout(anchor.getContext()); grid.setOrientation(LinearLayout.VERTICAL);
		LinearLayout row = null;
		for (int i = 0; i < commands.size(); i++) {
			if (i % columns == 0) {
				row = new LinearLayout(anchor.getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
				grid.addView(row, new LinearLayout.LayoutParams(-1, cell));
			}
			View original = commands.get(i);
			View proxy = createGlyph(original, tags.get(original), text, accent);
			proxy.setOnClickListener(v -> {
				if (released || selected) return;
				selected = true; dismiss(); select.accept(original);
			});
			row.addView(proxy, new LinearLayout.LayoutParams(cell, -1));
		}
		scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
		window.setWidth(bounds.width()); window.setHeight(bounds.height());
		anchor.addOnAttachStateChangeListener(attachListener); panel.addOnAttachStateChangeListener(attachListener);
		observer = panel.getViewTreeObserver();
		observer.addOnGlobalLayoutListener(layoutListener); observer.addOnScrollChangedListener(scrollListener);
		try {
			// showAsDropDown can requestRectangleOnScreen on the ancestor ScrollView,
			// which moves the editor and immediately invalidates/dismisses the popup.
			// Bounds already use the visible screen viewport: do not ask the form to scroll.
			window.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, bounds.left, bounds.top);
		} catch (WindowManager.BadTokenException e) { dismiss(); }
	}

	static View createGlyph(View original, Integer tag, int text, int accent) {
		Context context = original.getContext();
		View proxy = null;
		if (tag != null) for (MarkupButtonProvider provider : MarkupButtonProvider.iterable(tag)) {
			if (provider.tag == tag) {
				Button button = provider.createButton(context, android.R.attr.borderlessButtonStyle);
				provider.applyTextAndStyle(button); proxy = button; break;
			}
		}
		if (proxy == null && original instanceof ImageView) {
			Drawable icon = ((ImageView) original).getDrawable();
			if (icon != null && icon.getConstantState() != null) {
				ImageButton button = new ImageButton(context, null, android.R.attr.borderlessButtonStyle);
				button.setImageDrawable(icon.getConstantState().newDrawable(context.getResources()).mutate());
				button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
				button.setImageTintList(ColorStateList.valueOf(text)); proxy = button;
			}
		}
		if (proxy == null) {
			Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
			button.setText(original instanceof TextView ? ((TextView) original).getText() : original.getContentDescription());
			proxy = button;
		}
		int radius = Math.round(ResourceUtils.obtainDensity(original) * 12);
		GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
		proxy.setBackgroundTintList(null);
		proxy.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 32)), null, mask));
		proxy.setMinimumWidth(0); proxy.setMinimumHeight(0);
		if (proxy instanceof TextView) {
			TextView label = (TextView) proxy;
			label.setMinWidth(0); label.setMinHeight(0); label.setTextColor(text);
			if (original instanceof TextView) {
				TextView source = (TextView) original;
				label.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
				label.setTypeface(source.getTypeface());
			} else ViewUtils.setTextSizeScaled(label, 14);
			label.setGravity(Gravity.CENTER);
			label.setSingleLine(true);
		}
		if (proxy instanceof Button) ((Button) proxy).setAllCaps(false);
		int padding = Math.round(ResourceUtils.obtainDensity(original) * (proxy instanceof ImageView ? 12 : 2));
		proxy.setPadding(padding, 0, padding, 0);
		proxy.setContentDescription(original.getContentDescription()); proxy.setTooltipText(original.getContentDescription());
		proxy.setTag(original.getTag());
		return proxy;
	}

	private static Rect screenBounds(View view) {
		int[] point = new int[2]; view.getLocationOnScreen(point);
		return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
	}

	static Rect calculateBounds(Rect viewport, Rect panel, Rect anchor, float density, int count, boolean rtl) {
		return calculateBounds(viewport, panel, anchor, density, count, rtl, Math.round(44 * density));
	}

	private static Rect calculateBounds(Rect viewport, Rect panel, Rect anchor, float density, int count, boolean rtl, int cell) {
		int margin = Math.round(8 * density), padding = Math.round(8 * density) * 2;
		int row = cell;
		int available = viewport.width() - margin * 2 - padding;
		int columns = Math.min(3, Math.min(count, available / Math.max(1, cell)));
		if (columns <= 0 || count <= 0) return null;
		int width = columns * cell + padding;
		int bottom = Math.min(panel.top - margin, viewport.bottom - margin);
		int height = Math.min(((count + columns - 1) / columns) * row + padding, bottom - viewport.top - margin);
		if (height < row + padding) return null;
		int left = rtl ? anchor.left : anchor.right - width;
		left = Math.max(viewport.left + margin, Math.min(left, viewport.right - margin - width));
		return new Rect(left, bottom - height, left + width, bottom);
	}

	void dismiss() { window.dismiss(); release(); }

	private void release() {
		if (released) return;
		released = true;
		anchor.removeOnAttachStateChangeListener(attachListener); panel.removeOnAttachStateChangeListener(attachListener);
		if (observer != null && observer.isAlive()) {
			observer.removeOnGlobalLayoutListener(layoutListener); observer.removeOnScrollChangedListener(scrollListener);
		}
		observer = null;
		if (onDismiss != null) { Runnable listener = onDismiss; onDismiss = null; listener.run(); }
	}
}
