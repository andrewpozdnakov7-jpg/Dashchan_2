package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Rect;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import org.junit.Test;

/** Source fixtures; execute on Android after explicit build/test authorization. */
public class CompactDialogUiTest {
	@Test public void experimentOffKeepsFormGeometryAndOnRetainsInputIdentity() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_COMPACT_DIALOGS);
			try {
				Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
				LinearLayout body = new LinearLayout(context);
				body.setPadding(11, 23, 17, 29);
				EditText input = new EditText(context); input.setText("draft"); input.setSelection(2);
				body.addView(input); ScrollView content = new ScrollView(context); content.addView(body);
				CompactDialogUi.markForm(content, body);
				Preferences.PREFERENCES.edit().remove(Preferences.KEY_COMPACT_DIALOGS).close();
				assertFalse(CompactDialogUi.isEnabled()); assertFalse(CompactDialogUi.prepareForm(content));
				assertEquals(23, body.getPaddingTop()); assertEquals(29, body.getPaddingBottom());
				Preferences.PREFERENCES.edit().put(Preferences.KEY_COMPACT_DIALOGS, true).close();
				assertTrue(CompactDialogUi.prepareForm(content));
				assertEquals(0, body.getPaddingTop());
				assertEquals(Math.round(24 * ResourceUtils.obtainDensity(body)), body.getPaddingStart());
				assertSame(input, body.getChildAt(0)); assertEquals("draft", input.getText().toString());
				assertEquals(2, input.getSelectionStart());
				assertTrue(CompactDialogUi.prepareForm(content)); assertEquals(0, body.getPaddingTop());
				LinearLayout unrelated = new LinearLayout(context); unrelated.setPadding(3, 7, 5, 9);
				assertFalse(CompactDialogUi.prepareForm(unrelated)); assertEquals(7, unrelated.getPaddingTop());
			} finally {
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_COMPACT_DIALOGS, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_COMPACT_DIALOGS).close();
			}
		});
	}
	@Test public void bottomAnchorOpensAboveWithoutLeavingViewport() {
		Rect result = StatusExplanationPopup.placement(new Rect(100, 50, 460, 650),
				new Rect(420, 605, 440, 625), 230, 136, 16, 8, false);
		assertNotNull(result); assertEquals(597, result.bottom);
		assertTrue(result.left >= 116); assertTrue(result.right <= 444);
		assertEquals(136, result.height());
	}
	@Test public void rtlAndLargeContentUseAvailableSpace() {
		Rect viewport = new Rect(200, 80, 520, 400);
		for (boolean rtl : new boolean[]{false, true}) {
			Rect result = StatusExplanationPopup.placement(viewport, new Rect(210, 200, 230, 220),
					320, 900, 16, 8, rtl);
			assertNotNull(result);
			assertTrue(result.left >= 216); assertTrue(result.right <= 504);
			assertTrue(result.top >= 96); assertTrue(result.bottom <= 384);
			assertTrue(result.height() > 0); assertTrue(result.height() < 900);
		}
		assertNull(StatusExplanationPopup.placement(viewport, new Rect(0, 0, 20, 20), 200, 50, 16, 8, false));
	}
	@Test public void partiallyVisibleAnchorsStillRespectEverySafeMargin() {
		Rect viewport = new Rect(100, 50, 460, 650);
		for (Rect anchor : new Rect[]{new Rect(110, 40, 130, 55), new Rect(430, 645, 450, 665)}) {
			Rect result = StatusExplanationPopup.placement(viewport, anchor, 320, 900, 16, 8, false);
			assertNotNull(result);
			assertTrue(result.left >= 116); assertTrue(result.right <= 444);
			assertTrue(result.top >= 66); assertTrue(result.bottom <= 634);
		}
	}
}
