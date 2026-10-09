package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlayerSpeedMenuTest {
	private static void onMain(java.util.function.Consumer<Context> test) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> test.accept(
				ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext())));
	}
	@Test public void presetsKeepOrderAndOnlyCurrentSpeedHasCheckmark() {
		onMain(context -> {
			int[] clicked = {-1};
			LinearLayout rows = PlayerSpeedMenu.createRows(context, new int[] {800, 1000, 1250, 1500},
					1250, false, index -> clicked[0] = index);
			assertEquals(4, rows.getChildCount());
			String[] titles = {"0.8x", "1x", "1.25x", "1.5x"};
			for (int i = 0; i < titles.length; i++) {
				LinearLayout row = (LinearLayout) rows.getChildAt(i);
				assertEquals(titles[i], ((TextView) row.getChildAt(0)).getText().toString());
				assertTrue(row.getChildAt(1) instanceof ImageView);
				assertEquals(i == 2, row.isSelected());
				assertEquals(i == 2 ? View.VISIBLE : View.INVISIBLE, row.getChildAt(1).getVisibility());
			}
			rows.getChildAt(3).performClick(); assertEquals(3, clicked[0]);
		});
	}
	@Test public void customSpeedUsesTheExistingCustomActionIndexAndAccessibleCheckedState() {
		onMain(context -> {
			int[] clicked = {-1};
			LinearLayout rows = PlayerSpeedMenu.createRows(context, new int[] {1000, 1500}, 1370,
					true, index -> clicked[0] = index);
			assertEquals(3, rows.getChildCount()); assertTrue(rows.getChildAt(2).isSelected());
			assertFalse(rows.getChildAt(0).isSelected()); assertFalse(rows.getChildAt(1).isSelected());
			AccessibilityNodeInfo info = rows.getChildAt(2).createAccessibilityNodeInfo();
			assertTrue(info.isCheckable());
			assertEquals(AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE,
					AccessibilityNodeInfoCompat.wrap(info).getChecked());
			for (int i = 0; i < 2; i++) {
				AccessibilityNodeInfo unchecked = rows.getChildAt(i).createAccessibilityNodeInfo();
				assertTrue(unchecked.isCheckable());
				assertEquals("android.widget.RadioButton", unchecked.getClassName().toString());
				assertEquals(AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE,
						AccessibilityNodeInfoCompat.wrap(unchecked).getChecked());
			}
			rows.getChildAt(2).performClick(); assertEquals(2, clicked[0]);
			rows = PlayerSpeedMenu.createRows(context, new int[] {1000, 1500}, 1500, true, index -> {});
			assertTrue(rows.getChildAt(1).isSelected()); assertFalse(rows.getChildAt(2).isSelected());
		});
	}
	@Test public void compactRowsUsePlayerTypographyAndRetainAccessibleTouchHeight() {
		onMain(context -> {
			LinearLayout rows = PlayerSpeedMenu.createRows(context, new int[] {1000}, 1000, true, index -> {});
			TextView reference = new TextView(context); PlayerControlsStyle.text(reference, 11);
			float density = context.getResources().getDisplayMetrics().density;
			for (int i = 0; i < rows.getChildCount(); i++) {
				LinearLayout row = (LinearLayout) rows.getChildAt(i);
				TextView label = (TextView) row.getChildAt(0);
				assertEquals(reference.getTextSize(), label.getTextSize(), 0f);
				assertEquals(reference.getTypeface(), label.getTypeface());
				assertEquals(reference.getShadowRadius(), label.getShadowRadius(), 0f);
				assertEquals(2, label.getMaxLines());
				assertEquals(Math.round(48 * density), row.getLayoutParams().height);
				assertEquals(Math.round(16 * density), row.getChildAt(1).getLayoutParams().width);
			}
		});
	}
	@Test public void legacyPolicyDoesNotCreateExperimentalPopup() {
		onMain(context -> {
			String key = Preferences.KEY_NEW_INTERFACE_MOTION;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			try {
				Preferences.PREFERENCES.edit().put(key, false).close();
				assertNull(PlayerSpeedMenu.create(new View(context), new View(context), new int[] {1000},
						1000, false, index -> fail()));
			} finally {
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(key, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
	@Test public void menuIsOneThirdNarrowerAndClearsTheWholeControlsPanel() {
		Rect panel = new Rect(8, 700, 128, 740);
		Rect bounds = PlayerSpeedMenu.calculateBounds(new Rect(0, 24, 360, 800), panel,
				new Rect(16, 704, 48, 736), 1f, 6, false);
		assertNotNull(bounds);
		assertEquals(87, bounds.width());
		assertEquals(12, panel.top - bounds.bottom);
		assertFalse(Rect.intersects(panel, bounds));
	}
	@Test public void landscapeMenuShrinksAndScrollsInsteadOfOverlappingControls() {
		Rect viewport = new Rect(0, 24, 800, 360);
		Rect panel = new Rect(8, 260, 128, 300);
		Rect bounds = PlayerSpeedMenu.calculateBounds(viewport, panel,
				new Rect(16, 264, 48, 296), 1f, 20, false);
		assertNotNull(bounds);
		assertEquals(32, bounds.top);
		assertEquals(248, bounds.bottom);
		assertFalse(Rect.intersects(panel, bounds));
	}
	@Test public void rightHandAndRtlAnchorsStayInsideTheVisibleWindow() {
		Rect viewport = new Rect(50, 24, 410, 800);
		Rect panel = new Rect(282, 700, 402, 740);
		Rect button = new Rect(362, 704, 394, 736);
		for (boolean rtl : new boolean[] {false, true}) {
			Rect bounds = PlayerSpeedMenu.calculateBounds(viewport, panel, button, 1f, 6, rtl);
			assertNotNull(bounds);
			assertTrue(bounds.left >= viewport.left + 8);
			assertTrue(bounds.right <= viewport.right - 8);
			assertEquals(12, panel.top - bounds.bottom);
			if (rtl) assertEquals(button.right, bounds.right);
		}
	}
	@Test public void insufficientSpaceDoesNotPlaceMenuOverControls() {
		assertNull(PlayerSpeedMenu.calculateBounds(new Rect(0, 24, 360, 800),
				new Rect(8, 80, 128, 120), new Rect(16, 84, 48, 116), 1f, 6, false));
	}
}
