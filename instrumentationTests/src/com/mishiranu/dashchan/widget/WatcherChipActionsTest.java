package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.service.WatcherService;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached routing fixtures; no service, network, preferences or saved favorites. */
@RunWith(AndroidJUnit4.class)
public class WatcherChipActionsTest {
	private WatcherView fixture(int direction, int[] clicks) {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		DrawerStyle style = new DrawerStyle(context);
		WatcherView view = new WatcherView(context, new WatcherView.ColorSet(0, 0, 0), style);
		view.setLayoutDirection(direction);
		view.setOnClickListener(v -> clicks[0]++);
		view.setOnRefreshClickListener(v -> clicks[1]++);
		view.bindThread(1, "Favorite");
		view.update(new WatcherService.Counter(WatcherService.Counter.State.ENABLED, false, 12, false, false));
		view.measure(View.MeasureSpec.makeMeasureSpec(style.chipWidth, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(style.rowHeight, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
		return view;
	}
	private void touch(WatcherView view, int action, boolean number) {
		float density = view.getResources().getDisplayMetrics().density;
		float x = number ? view.getWidth() - 12 * density : 16 * density;
		if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) x = view.getWidth() - x;
		long now = SystemClock.uptimeMillis();
		MotionEvent event = MotionEvent.obtain(now, now, action, x, view.getHeight() / 2f, 0);
		try { view.onTouchEvent(event); } finally { event.recycle(); }
	}
	@Test public void eyeTogglesAndNumberRefreshesInBothDirections() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				int[] clicks = new int[2]; WatcherView view = fixture(direction, clicks);
				touch(view, MotionEvent.ACTION_DOWN, false); view.performClick();
				touch(view, MotionEvent.ACTION_CANCEL, false);
				assertArrayEquals(new int[]{1, 0}, clicks);
				touch(view, MotionEvent.ACTION_DOWN, true);
				// An identical-ID rebind during the gesture must not change its selected action.
				view.bindThread(1, "Favorite updated"); view.performClick();
				touch(view, MotionEvent.ACTION_CANCEL, true);
				assertArrayEquals(new int[]{1, 1}, clicks);
				touch(view, MotionEvent.ACTION_DOWN, true);
				view.bindThread(2, "Different favorite");
				assertFalse(view.performClick());
				touch(view, MotionEvent.ACTION_CANCEL, true);
				assertArrayEquals(new int[]{1, 1}, clicks);
			}
		});
	}
	@Test public void disabledTrackingAndSelectionCannotRefreshOrAccidentallyToggle() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] clicks = new int[2]; WatcherView view = fixture(View.LAYOUT_DIRECTION_LTR, clicks);
			view.update(new WatcherService.Counter(WatcherService.Counter.State.DISABLED, true, 42, false, true));
			touch(view, MotionEvent.ACTION_DOWN, true); assertFalse(view.performClick());
			touch(view, MotionEvent.ACTION_CANCEL, true);
			assertArrayEquals(new int[]{0, 0}, clicks);
			view.update(new WatcherService.Counter(WatcherService.Counter.State.ENABLED, false, 0, false, false));
			view.setEnabled(false);
			assertFalse(view.performAccessibilityAction(R.id.drawer_refresh_favorite, null));
			assertFalse(view.performClick()); assertArrayEquals(new int[]{0, 0}, clicks);
		});
	}
	@Test public void accessibilityHasSeparateRefreshActionAndCancelDoesNotDispatch() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] clicks = new int[2]; WatcherView view = fixture(View.LAYOUT_DIRECTION_LTR, clicks);
			AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
			assertTrue(info.getActionList().stream().anyMatch(a -> a.getId() == R.id.drawer_refresh_favorite));
			assertTrue(view.performAccessibilityAction(R.id.drawer_refresh_favorite, null));
			assertArrayEquals(new int[]{0, 1}, clicks);
			touch(view, MotionEvent.ACTION_DOWN, true); touch(view, MotionEvent.ACTION_CANCEL, true);
			assertArrayEquals(new int[]{0, 1}, clicks);
		});
	}
}
