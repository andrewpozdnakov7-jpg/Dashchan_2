package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import com.mishiranu.dashchan.R;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.ImageButton;
import android.widget.ActionMenuView;
import android.widget.TextView;
import android.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import com.mishiranu.dashchan.content.Preferences;

/** Keeps navigation compact without shrinking its touch target below 48 dp. */
public class CompactToolbar extends Toolbar {
	private final boolean fixedActionSpacing;
	private ActionMenuView actionMenu;
	private int originalMenuMinimum;
	private final float[] iconCenter = new float[2];

	public CompactToolbar(Context context, AttributeSet attrs) {
		super(toolbarContext(context), attrs);
		fixedActionSpacing = Preferences.isS24UltraOneUi9ToolbarFixEnabled();
		ThemeEngine.applyToolbarStyle(this);
	}

	private static Context toolbarContext(Context context) {
		// Scope the opt-in geometry to this toolbar, before its native menu buttons are created.
		return Preferences.isS24UltraOneUi9ToolbarFixEnabled()
				? new ContextThemeWrapper(context, R.style.Theme_CompactToolbar_S24UltraFix) : context;
	}

	public void refreshMotionPolicy() { ToolbarSearchMotion.finish(this); }

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		return getTag(R.id.screen_motion_owner) != null || getTag(R.id.toolbar_search_motion_owner) != null
				|| super.dispatchTouchEvent(event);
	}

	@Override protected void onDetachedFromWindow() {
		ToolbarSearchMotion.finish(this);
		super.onDetachedFromWindow();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		Drawable navigationIcon = getNavigationIcon();
		if (navigationIcon != null) {
			int width = Math.round(48f * getResources().getDisplayMetrics().density);
			for (int i = 0; i < getChildCount(); i++) {
				View child = getChildAt(i);
				// Only the actual navigation button: leave menu, search and custom views alone.
				if (child instanceof ImageButton && ((ImageButton) child).getDrawable() == navigationIcon) {
					if (child.getMinimumWidth() != width) {
						child.setMinimumWidth(width);
					}
					ViewGroup.LayoutParams params = child.getLayoutParams();
					params.width = width;
					break;
				}
			}
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		if (fixedActionSpacing) {
			// The presenter may replace visible items when their measured widths change.
			// Bound remeasurement and let Toolbar reserve the menu width before measuring its title.
			for (int pass = 0; pass < 2 && measureActionSlots(); pass++) {
				super.onMeasure(widthMeasureSpec, heightMeasureSpec);
			}
		}
	}

	private ActionMenuView findActionMenu() {
		for (int i = 0; i < getChildCount(); i++) {
			if (getChildAt(i) instanceof ActionMenuView) return (ActionMenuView) getChildAt(i);
		}
		return null;
	}

	private View findOverflow(ActionMenuView menu) {
		Drawable icon = getOverflowIcon();
		for (int i = 0; i < menu.getChildCount(); i++) {
			View child = menu.getChildAt(i);
			if (child.getVisibility() == VISIBLE && child instanceof ImageButton
					&& icon != null && ((ImageButton) child).getDrawable() == icon) return child;
		}
		return null;
	}

	private int iconActionCount(ActionMenuView menu, View overflow) {
		if (overflow == null) return 0;
		Menu items = menu.getMenu();
		for (int i = 0; i < items.size(); i++) if (items.getItem(i).isActionViewExpanded()) return 0;
		int count = 0;
		for (int i = 0; i < menu.getChildCount(); i++) {
			View child = menu.getChildAt(i);
			if (child.getVisibility() == GONE || child == overflow) continue;
			MenuItem item = items.findItem(child.getId());
			// Search, custom action views and actions with labels keep their native layout.
			if (!(child instanceof TextView) || ((TextView) child).length() != 0 || item == null
					|| item.getIcon() == null || item.getActionView() != null) return 0;
			count++;
		}
		return count;
	}

	private int actionStep(View overflow) {
		int minimum = Math.round(48f * getResources().getDisplayMetrics().density);
		// Keep the existing overflow button and its hit area intact, even if an OEM makes it wider.
		return Math.max(minimum, overflow.getMeasuredWidth() + Math.abs(overflow.getPaddingLeft() - overflow.getPaddingRight()));
	}

	private boolean measureActionSlots() {
		ActionMenuView menu = findActionMenu();
		if (menu == null) return false;
		if (menu != actionMenu) { actionMenu = menu; originalMenuMinimum = menu.getMinimumWidth(); }
		View overflow = findOverflow(menu);
		int count = iconActionCount(menu, overflow);
		if (count == 0) {
			if (menu.getMinimumWidth() == originalMenuMinimum) return false;
			menu.setMinimumWidth(originalMenuMinimum);
			return true;
		}
		int step = actionStep(overflow);
		boolean changed = false;
		for (int i = 0; i < menu.getChildCount(); i++) {
			View child = menu.getChildAt(i);
			if (child.getVisibility() == GONE || child == overflow) continue;
			ViewGroup.LayoutParams params = child.getLayoutParams();
			if (params.width != step || child.getPaddingRight() != 0) {
				params.width = step;
				// ActionMenuItemView computes its centered left padding during measurement.
				child.setPadding(0, child.getPaddingTop(), 0, child.getPaddingBottom());
				child.forceLayout(); changed = true;
			}
		}
		int reserve = (count + 1) * step + menu.getPaddingLeft() + menu.getPaddingRight();
		ViewGroup.LayoutParams overflowParams = overflow.getLayoutParams();
		if (overflowParams instanceof ViewGroup.MarginLayoutParams) {
			ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) overflowParams;
			reserve += Math.max(0, margins.leftMargin) + Math.max(0, margins.rightMargin);
		}
		reserve = Math.max(reserve, originalMenuMinimum);
		if (menu.getMinimumWidth() != reserve) { menu.setMinimumWidth(reserve); changed = true; }
		if (changed) menu.forceLayout();
		return changed;
	}

	@Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		if (!fixedActionSpacing) return;
		ActionMenuView menu = findActionMenu();
		if (menu == null) return;
		View overflow = findOverflow(menu);
		int count = iconActionCount(menu, overflow);
		if (count == 0) return;
		int step = actionStep(overflow);
		ImageButton button = (ImageButton) overflow;
		Drawable icon = button.getDrawable();
		iconCenter[0] = icon.getBounds().exactCenterX(); iconCenter[1] = icon.getBounds().exactCenterY();
		button.getImageMatrix().mapPoints(iconCenter);
		float anchor = button.getLeft() + button.getPaddingLeft() + iconCenter[0];
		float offset = anchor - (button.getLeft() + button.getWidth() / 2f);
		step = Math.max(step, (int) Math.ceil(button.getWidth() + 2f * Math.abs(offset)));
		boolean rtl = menu.getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int direction = rtl ? 1 : -1;
		int near = Math.round(anchor + direction * step - step / 2f);
		int far = Math.round(anchor + direction * step * count - step / 2f);
		// Never place an action outside the menu's measured area or across the overflow hit target.
		if (Math.min(near, far) < 0 || Math.max(near, far) + step > menu.getWidth()
				|| (rtl ? near < button.getRight() : near + step > button.getLeft())) return;
		int index = 0;
		for (int i = menu.getChildCount() - 1; i >= 0; i--) {
			View child = menu.getChildAt(i);
			if (child.getVisibility() == GONE || child == overflow) continue;
			int x = Math.round(anchor + direction * step * ++index - step / 2f);
			int childTop = child.getTop(), childBottom = child.getBottom();
			if (child.getMeasuredWidth() != step) child.measure(MeasureSpec.makeMeasureSpec(step, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(childBottom - childTop, MeasureSpec.EXACTLY));
			child.layout(x, childTop, x + step, childBottom);
		}
	}
}
