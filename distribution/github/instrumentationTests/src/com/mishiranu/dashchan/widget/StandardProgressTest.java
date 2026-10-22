package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Disposable visual fixtures only; no network, backup, deletion, preferences or task execution. */
public class StandardProgressTest {
	private Context context() {
		Context c = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		ThemeEngine.applyTheme(c); return c;
	}
	@Test public void unknownKnownAndBackKeepSpansRawCountsAndLatestTotal() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			StandardProgressContent v = new StandardProgressContent(context(), "%d / %d");
			SpannableString text = new SpannableString("Synthetic fixture");
			StyleSpan span = new StyleSpan(android.graphics.Typeface.BOLD); text.setSpan(span, 0, 4, 0);
			v.message(text); v.state(3, 0, false);
			assertEquals(View.VISIBLE, v.waiting.getVisibility()); assertEquals(View.GONE, v.percent.getParent() instanceof View ? ((View) v.percent.getParent()).getVisibility() : -1);
			assertEquals(0, v.percent.length()); assertEquals(3, v.values.value);
			v.state(3, 4, false); assertEquals(String.format("%d / %d", 3, 4), v.count.getText().toString()); assertEquals(.75f, v.line.fraction, 0);
			assertEquals(View.GONE, v.waiting.getVisibility()); assertEquals(View.VISIBLE, v.line.getVisibility());
			assertEquals(1, ((android.text.Spanned) v.message.getText()).getSpans(0, 4, StyleSpan.class).length);
			v.state(3, 4, true); assertEquals(View.GONE, v.line.getVisibility()); assertEquals(0, v.count.length());
			v.state(3, 8, false); assertEquals(String.format("%d / %d", 3, 8), v.count.getText().toString()); assertEquals(.375f, v.line.fraction, 0);
			v.state(0, 8, false); assertEquals(0f, v.line.fraction, 0);
		});
	}
	@Test public void narrowCaptionsStackWithoutShrinkingTypeAndLineKeepsSixDp() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			StandardProgressContent v = new StandardProgressContent(context(), "%d / %d");
			v.state(Integer.MAX_VALUE, Integer.MAX_VALUE, false); float size = v.count.getTextSize();
			v.measure(View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(v, 180), View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(v, 300), View.MeasureSpec.AT_MOST));
			v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
			assertEquals(android.widget.LinearLayout.VERTICAL, ((android.widget.LinearLayout) v.count.getParent()).getOrientation());
			assertEquals(size, v.count.getTextSize(), 0); assertEquals(StandardAppearance.dp(v, 6), v.line.getHeight());
			assertTrue(v.count.getWidth() > 0);
		});
	}
	@Test public void legacyConstructionDoesNotBecomeStandardAndDismissIsNotCancel() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> {
				Context c = PostingTestContext.create(a); int[] events = new int[2];
				ProgressDialog legacy = new ProgressDialog(c, "%d / %d"); assertNull(legacy.standardContent);
				legacy.setMax(10); legacy.setValue(5); legacy.setMessage("Synthetic operation");
				legacy.setOnCancelListener(d -> events[0]++); legacy.setOnDismissListener(d -> events[1]++);
				legacy.show(); legacy.onStart(); legacy.onStart(); legacy.dismiss();
				assertEquals(0, events[0]); assertEquals(1, events[1]);
				legacy.show(); legacy.cancel(); assertEquals(1, events[0]); assertEquals(2, events[1]);
			});
		}
	}
	@Test public void standardFactoryPreservesCallerListenersAndLatestStateAcrossShow() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> {
				Context c = PostingTestContext.create(a); int[] events = new int[2];
				ProgressDialog d = ProgressDialog.standard(c, "%d / %d");
				d.setValue(7); d.setMax(10); d.setMessage("Synthetic operation");
				d.setOnCancelListener(x -> events[0]++); d.setOnDismissListener(x -> events[1]++);
				d.show(); d.onStart(); d.onStart();
				if (d.standardContent != null) {
					StandardProgressContent v = (StandardProgressContent) d.standardContent;
					assertEquals(String.format("%d / %d", 7, 10), v.count.getText().toString()); assertEquals(1, v.getChildCount());
				}
				d.dismiss(); assertEquals(0, events[0]); assertEquals(1, events[1]);
				d.show(); d.cancel(); assertEquals(1, events[0]); assertEquals(2, events[1]);
			});
		}
	}
}
