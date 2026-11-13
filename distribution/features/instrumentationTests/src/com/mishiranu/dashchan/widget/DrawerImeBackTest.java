package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;

/** Disposable attached drawer + real IME. Native priority/RTL additionally need the manual OEM matrix. */
public class DrawerImeBackTest {
	@Test public void cancelKeepsImeAndCommitHidesOnlyImeBeforeNextButtonBackClosesDrawer() {
		Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
		DrawerLayout[] drawer = {null}; View[] panel = {null}; EditText[] edit = {null}; DrawerBackController[] back = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				drawer[0] = new DrawerLayout(activity);
				FrameLayout page = new FrameLayout(activity); page.setTag("same page"); drawer[0].addView(page,
						new DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
				edit[0] = new EditText(activity); edit[0].setText("keep drawer input"); panel[0] = edit[0];
				drawer[0].addView(panel[0], new DrawerLayout.LayoutParams(280, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT));
				activity.setContentView(drawer[0]); back[0] = new DrawerBackController(drawer[0], panel[0], () -> {});
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				drawer[0].openDrawer(panel[0], false); edit[0].requestFocus();
				activity.getSystemService(InputMethodManager.class).showSoftInput(edit[0], InputMethodManager.SHOW_IMPLICIT);
			});
			boolean[] ime = {false};
			for (int i = 0; i < 40; i++) {
				scenario.onActivity(a -> ime[0] = PredictiveBackMotion.isImeVisible(drawer[0])); if (ime[0]) break; SystemClock.sleep(50);
			}
			Assume.assumeTrue("Device must expose a software IME", ime[0]);
			scenario.onActivity(activity -> {
				assertTrue(back[0].start()); back[0].progress(.7f); back[0].recover(true);
				assertTrue(drawer[0].isDrawerOpen(panel[0])); assertTrue(PredictiveBackMotion.isImeVisible(drawer[0]));
				assertTrue(back[0].start()); back[0].commit(); assertTrue(drawer[0].isDrawerOpen(panel[0]));
				assertEquals("keep drawer input", edit[0].getText().toString());
			});
			for (int i = 0; i < 40; i++) {
				scenario.onActivity(a -> ime[0] = PredictiveBackMotion.isImeVisible(drawer[0])); if (!ime[0]) break; SystemClock.sleep(50);
			}
			assertFalse(ime[0]);
			scenario.onActivity(activity -> {
				back[0].commit(); assertFalse(drawer[0].isDrawerVisible(panel[0]));
				assertEquals("same page", drawer[0].getChildAt(0).getTag()); back[0].suspend();
			});
		}
	}
}
