package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Narrow attached toolbar host: real editable query, focus/selection and the production presentation/session. */
public class PredictiveSearchPresentationTest {
	@Test public void previewNeverCancelsTheQueryAndAChangedQueryCannotReceiveAnOldCommit() {
		FrameLayout[] toolbar = {null}; EditText[] search = {null}; TextView[] title = {null};
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				toolbar[0] = new FrameLayout(a); search[0] = new EditText(a); search[0].setText("keep query");
				toolbar[0].addView(search[0], new FrameLayout.LayoutParams(240, 56)); a.setContentView(toolbar[0]);
				title[0] = new TextView(a); title[0].setText("Original page"); title[0].layout(0, 0, 160, 40);
				search[0].requestFocus(); search[0].setSelection(1, 4); search[0].setAlpha(.7f); search[0].setTranslationX(5f);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				int[] cancellations = {0};
				PredictiveTransition first = new PredictiveTransition(toolbar[0], new PredictiveSearchPresentation(
						toolbar[0], title[0], search[0], () -> search[0].getText().toString().equals("keep query")));
				first.progress(.8f); first.progress(.2f);
				assertEquals("keep query", search[0].getText().toString()); assertEquals(1, search[0].getSelectionStart());
				assertEquals(4, search[0].getSelectionEnd()); assertTrue(search[0].hasFocus());
				first.finish(); assertEquals(.7f, search[0].getAlpha(), 0f); assertEquals(5f, search[0].getTranslationX(), 0f);
				PredictiveTransition oldQuery = new PredictiveTransition(toolbar[0], new PredictiveSearchPresentation(
						toolbar[0], title[0], search[0], () -> search[0].getText().toString().equals("keep query")));
				oldQuery.progress(.4f); search[0].setText("new query"); oldQuery.progress(.6f);
				assertFalse(oldQuery.commit(() -> cancellations[0]++)); assertEquals(0, cancellations[0]);
				assertEquals("new query", search[0].getText().toString());
				PredictiveTransition current = new PredictiveTransition(toolbar[0], new PredictiveSearchPresentation(
						toolbar[0], title[0], search[0], () -> true));
				current.progress(.3f); assertTrue(current.commit(() -> { cancellations[0]++; search[0].setText(""); }));
				current.finish(); current.finish(); assertEquals(1, cancellations[0]); assertEquals("", search[0].getText().toString());
			});
		}
	}
}
