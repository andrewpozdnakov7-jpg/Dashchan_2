package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ActionMenuView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toolbar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** Real native menu items: geometry, overflow anchor, visibility and touch routing. */
public class CompactToolbarSpacingTest {
	private static final int FIRST = 11001, SECOND = 11002;
	private static ColorDrawable icon(Context context) {
		int size = Math.round(24f * context.getResources().getDisplayMetrics().density);
		return new ColorDrawable(Color.WHITE) {
			@Override public int getIntrinsicWidth() { return size; }
			@Override public int getIntrinsicHeight() { return size; }
		};
	}
	private static void configure(Toolbar toolbar, boolean rtl) {
		toolbar.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
		toolbar.setTitle("Toolbar title"); toolbar.setOverflowIcon(icon(toolbar.getContext()));
		Menu menu = toolbar.getMenu();
		menu.add(0, FIRST, 0, "First").setIcon(icon(toolbar.getContext())).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, SECOND, 1, "Second").setIcon(icon(toolbar.getContext())).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, 11003, 2, "More").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
	}
	private static ActionMenuView menu(Toolbar toolbar) {
		for (int i = 0; i < toolbar.getChildCount(); i++) if (toolbar.getChildAt(i) instanceof ActionMenuView)
			return (ActionMenuView) toolbar.getChildAt(i);
		throw new AssertionError("No native menu");
	}
	private static View overflow(Toolbar toolbar) {
		ActionMenuView menu = menu(toolbar);
		for (int i = 0; i < menu.getChildCount(); i++) {
			View child = menu.getChildAt(i);
			if (child instanceof ImageButton && ((ImageButton) child).getDrawable() == toolbar.getOverflowIcon()) return child;
		}
		throw new AssertionError("No native overflow");
	}
	private static float center(Toolbar toolbar, View child) {
		return menu(toolbar).getLeft() + child.getLeft() + child.getWidth() / 2f;
	}
	private static void layout(FrameLayout root, int width, int height) {
		root.forceLayout();
		root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		root.layout(0, 0, width, height);
	}
	private static void tap(Toolbar toolbar, View child) {
		ActionMenuView menu = menu(toolbar);
		float x = center(toolbar, child), y = menu.getTop() + child.getTop() + child.getHeight() / 2f;
		long now = SystemClock.uptimeMillis();
		MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
		MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0);
		try { toolbar.dispatchTouchEvent(down); toolbar.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
	}
	@Test public void enabledFixKeepsOverflowAnchorAndEqualHitSlotsInBothDirectionsAndAfterHidingAnAction() {
		String key = Preferences.KEY_S24_ULTRA_ONEUI9_TOOLBAR_FIX;
		Object old = Preferences.PREFERENCES.getAll().get(key);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			for (boolean rtl : new boolean[] {false, true}) {
				scenario.onActivity(a -> {
					Preferences.PREFERENCES.edit().put(key, true).close();
					CompactToolbar toolbar = new CompactToolbar(ThemeEngine.attach(a), null);
					Toolbar reference = new Toolbar(toolbar.getContext());
					configure(toolbar, rtl); configure(reference, rtl);
					FrameLayout root = new FrameLayout(a);
					int height = Math.round(56f * a.getResources().getDisplayMetrics().density);
					root.addView(reference, new FrameLayout.LayoutParams(-1, height));
					FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, height); params.topMargin = height;
					root.addView(toolbar, params); a.setContentView(root);
				});
				InstrumentationRegistry.getInstrumentation().waitForIdleSync();
				scenario.onActivity(a -> {
					FrameLayout content = a.findViewById(android.R.id.content);
					FrameLayout root = (FrameLayout) content.getChildAt(0);
					Toolbar reference = (Toolbar) root.getChildAt(0);
					CompactToolbar toolbar = (CompactToolbar) root.getChildAt(1);
					int width = Math.round(400f * a.getResources().getDisplayMetrics().density);
					int height = Math.round(112f * a.getResources().getDisplayMetrics().density);
					layout(root, width, height);
					View first = menu(toolbar).findViewById(FIRST), second = menu(toolbar).findViewById(SECOND);
					View more = overflow(toolbar);
					float anchor = center(toolbar, more), step = second.getWidth();
					assertEquals(center(reference, overflow(reference)), anchor, 1f);
					assertEquals(first.getWidth(), second.getWidth());
					assertTrue(step >= 48f * a.getResources().getDisplayMetrics().density - 1f);
					assertEquals(step, Math.abs(center(toolbar, first) - center(toolbar, second)), 1f);
					assertEquals(step, Math.abs(center(toolbar, second) - anchor), 1f);
					int[] tapped = {0}; toolbar.setOnMenuItemClickListener(item -> true);
					first.setOnTouchListener((view, event) -> {
						if (event.getActionMasked() == MotionEvent.ACTION_DOWN) tapped[0] = FIRST;
						return false;
					});
					second.setOnTouchListener((view, event) -> {
						if (event.getActionMasked() == MotionEvent.ACTION_DOWN) tapped[0] = SECOND;
						return false;
					});
					tap(toolbar, first); assertEquals(FIRST, tapped[0]);
					tap(toolbar, second); assertEquals(SECOND, tapped[0]);
					toolbar.getMenu().findItem(FIRST).setVisible(false);
					layout(root, width, height);
					assertEquals(anchor, center(toolbar, overflow(toolbar)), 1f);
					assertEquals(step, Math.abs(center(toolbar, menu(toolbar).findViewById(SECOND)) - anchor), 1f);
				});
			}
		} finally {
			try (com.mishiranu.dashchan.util.SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
				if (old instanceof Boolean) editor.put(key, (Boolean) old); else editor.remove(key);
			}
		}
	}
}
