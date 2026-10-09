package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.transition.Fade;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.media.PlaybackSpeed;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.function.IntConsumer;

/** Experimental menu presentation only. Selection and persistence belong to VideoUnit. */
final class PlayerSpeedMenu {
	private final View anchor;
	private final View controlsPanel;
	private final PopupWindow window;
	private final int rowCount;
	private Runnable onDismiss;
	private OneShotPreDrawListener preDraw;
	private boolean released;
	private boolean selected;
	private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { dismiss(); }
	};
	private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
		if (l != ol || t != ot || r != or || b != ob) dismiss();
	};

	static PlayerSpeedMenu create(View anchor, View controlsPanel, int[] speeds, int current,
			boolean custom, IntConsumer select) {
		return InterfaceMotion.isEnabled()
				? new PlayerSpeedMenu(anchor, controlsPanel, speeds, current, custom, select) : null;
	}

	private PlayerSpeedMenu(View anchor, View controlsPanel, int[] speeds, int current,
			boolean custom, IntConsumer select) {
		this.anchor = anchor;
		this.controlsPanel = controlsPanel;
		rowCount = speeds.length + (custom ? 1 : 0);
		Context context = anchor.getContext();
		float density = context.getResources().getDisplayMetrics().density;
		ScrollView scroll = new ScrollView(context);
		scroll.setFillViewport(false);
		scroll.setClipToPadding(true);
		scroll.setPadding(0, dp(density, 6), 0, dp(density, 6));
		scroll.setVerticalScrollBarEnabled(false);
		scroll.setAccessibilityPaneTitle(context.getString(R.string.playback_speed));
		scroll.addView(createRows(context, speeds, current, custom, index -> {
			if (released || selected) return;
			selected = true;
			dismiss();
			select.accept(index);
		}));
		window = new PopupWindow(scroll, 0, 0, true);
		window.setBackgroundDrawable(PlayerControlsStyle.panel(density, 16));
		window.setElevation(dp(density, 8));
		window.setOutsideTouchable(true);
		window.setClippingEnabled(true);
		window.setOverlapAnchor(false);
		window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
		Fade enter = new Fade(); enter.setDuration(InterfaceMotion.duration(160));
		enter.setInterpolator(InterfaceMotion.STANDARD); window.setEnterTransition(enter);
		Fade exit = new Fade(); exit.setDuration(InterfaceMotion.duration(100));
		exit.setInterpolator(InterfaceMotion.STANDARD); window.setExitTransition(exit);
		window.setOnDismissListener(this::release);
	}

	void setOnDismissListener(Runnable listener) { onDismiss = listener; }

	boolean show() {
		if (released) return false;
		if (!anchor.isAttachedToWindow() || anchor.getWindowToken() == null || !anchor.isShown()
				|| !controlsPanel.isAttachedToWindow() || !controlsPanel.isShown()) {
			release(); return false;
		}
		float density = anchor.getResources().getDisplayMetrics().density;
		Rect viewport = new Rect(); anchor.getWindowVisibleDisplayFrame(viewport);
		int[] location = new int[2]; anchor.getLocationOnScreen(location);
		Rect buttonBounds = new Rect(location[0], location[1], location[0] + anchor.getWidth(),
				location[1] + anchor.getHeight());
		controlsPanel.getLocationOnScreen(location);
		Rect panelBounds = new Rect(location[0], location[1], location[0] + controlsPanel.getWidth(),
				location[1] + controlsPanel.getHeight());
		Rect bounds = calculateBounds(viewport, panelBounds, buttonBounds, density, rowCount,
				anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
		if (bounds == null) { release(); return false; }
		window.setWidth(bounds.width()); window.setHeight(bounds.height());
		anchor.addOnAttachStateChangeListener(attachListener);
		anchor.addOnLayoutChangeListener(layoutListener);
		controlsPanel.addOnAttachStateChangeListener(attachListener);
		controlsPanel.addOnLayoutChangeListener(layoutListener);
		try {
			// Reserve space above the whole capsule, including its padding, not just the button.
			// Explicit offsets prevent platform above-anchor placement from covering the capsule.
			window.showAsDropDown(controlsPanel, bounds.left - panelBounds.left,
					bounds.top - panelBounds.bottom, Gravity.LEFT);
			ScrollView scroll = (ScrollView) window.getContentView();
			preDraw = OneShotPreDrawListener.add(scroll, () -> {
				preDraw = null;
				if (released || !window.isShowing()) return;
				LinearLayout rows = (LinearLayout) scroll.getChildAt(0);
				for (int i = 0; i < rows.getChildCount(); i++) {
					View row = rows.getChildAt(i);
					if (row.isSelected()) {
						scroll.scrollTo(0, Math.max(0, row.getTop() - (scroll.getHeight() - row.getHeight()) / 2));
						break;
					}
				}
			});
			return true;
		} catch (WindowManager.BadTokenException e) {
			dismiss(); return false;
		}
	}

	void dismiss() {
		window.dismiss();
		release();
	}
	private void release() {
		if (released) return;
		released = true;
		if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
		anchor.removeOnAttachStateChangeListener(attachListener);
		anchor.removeOnLayoutChangeListener(layoutListener);
		controlsPanel.removeOnAttachStateChangeListener(attachListener);
		controlsPanel.removeOnLayoutChangeListener(layoutListener);
		if (onDismiss != null) { Runnable listener = onDismiss; onDismiss = null; listener.run(); }
	}

	static Rect calculateBounds(Rect viewport, Rect panel, Rect button, float density, int rowCount,
			boolean alignRight) {
		int margin = dp(density, 8);
		int gap = dp(density, 12);
		int width = Math.min(Math.round(130f * density * 2f / 3f), viewport.width() - margin * 2);
		int bottom = Math.min(panel.top - gap, viewport.bottom - margin);
		int height = Math.min(dp(density, Math.min(360, rowCount * 48 + 12)),
				bottom - viewport.top - margin);
		if (width <= 0 || height < dp(density, 48)) return null;
		int left = alignRight ? button.right - width : button.left;
		left = Math.max(viewport.left + margin, Math.min(left, viewport.right - margin - width));
		return new Rect(left, bottom - height, left + width, bottom);
	}

	static LinearLayout createRows(Context context, int[] speeds, int current, boolean custom, IntConsumer select) {
		float density = context.getResources().getDisplayMetrics().density;
		LinearLayout rows = new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL);
		boolean presetSelected = false;
		for (int i = 0; i < speeds.length; i++) {
			boolean checked = speeds[i] == current; presetSelected |= checked;
			addRow(rows, PlaybackSpeed.format(speeds[i]), checked, i, density, select);
		}
		if (custom) addRow(rows, context.getString(R.string.custom_playback_speed), !presetSelected,
				speeds.length, density, select);
		return rows;
	}

	private static void addRow(LinearLayout rows, String title, boolean checked, int index,
			float density, IntConsumer select) {
		Context context = rows.getContext();
		LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL); row.setSelected(checked); row.setFocusable(true);
		row.setPaddingRelative(dp(density, 4), 0, dp(density, 4), 0);
		row.setContentDescription(title);
		row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26ffffff),
				rounded(checked ? 0x14ffffff : Color.TRANSPARENT, dp(density, 8)),
				rounded(Color.WHITE, dp(density, 8))));
		TextView label = new TextView(context); PlayerControlsStyle.text(label, 11);
		label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		// Keep longer/custom labels readable without expanding the compact menu.
		label.setSingleLine(false); label.setMaxLines(2);
		label.setEllipsize(TextUtils.TruncateAt.END); label.setText(title);
		label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
		ImageView check = new ImageView(context); check.setImageResource(R.drawable.ic_editor_done);
		check.setImageTintList(ColorStateList.valueOf(Color.WHITE));
		check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
		check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(density, 16), dp(density, 16));
		checkParams.setMarginStart(dp(density, 4)); row.addView(check, checkParams);
		row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.RadioButton"); info.setCheckable(true);
				AccessibilityNodeInfoCompat.wrap(info).setChecked(checked
						? AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
						: AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE);
			}
		});
		row.setOnClickListener(view -> select.accept(index));
		LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, dp(density, 48));
		rowParams.setMargins(dp(density, 4), 0, dp(density, 4), 0); rows.addView(row, rowParams);
	}
	private static GradientDrawable rounded(int color, int radius) {
		GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius);
		return drawable;
	}
	private static int dp(float density, int value) { return Math.round(density * value); }
}
