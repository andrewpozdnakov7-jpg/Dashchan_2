package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Deterministic viewport geometry, not a claim of testing a real keyboard on a device. */
@RunWith(AndroidJUnit4.class)
public class PostingKeyboardLayoutTest {
	private static final class ProbeScroll extends PostingScrollView {
		ProbeScroll(Context context) { super(context); }
		int focusDelta(Rect bounds) { return computeScrollDeltaToGetChildRectOnScreen(bounds); }
	}
	private static void layout(View root, int width, int height) {
		root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		root.layout(0, 0, width, height);
	}

	@Test public void viewportOwnerPreventsNativeFocusRevealButNotManualScrolling() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			ProbeScroll scroll = new ProbeScroll(context);
			scroll.addView(new View(context), new FrameLayout.LayoutParams(400, 2000));
			layout(scroll, 400, 800);
			Rect far = new Rect(0, 1200, 400, 1500);
			assertTrue(scroll.focusDelta(far) > 0);
			scroll.setEditorViewportOwner(() -> true);
			assertEquals(0, scroll.focusDelta(far));
			scroll.scrollTo(0, 200); assertEquals(200, scroll.getScrollY());
			scroll.setEditorViewportOwner(() -> false);
			assertTrue(scroll.focusDelta(far) > 0);
			scroll.setEditorViewportOwner(null);
			assertTrue(scroll.focusDelta(far) > 0);
		});
	}

	@Test public void alignmentReplacesAnAlreadyRunningSmoothScrollTarget() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			ProbeScroll scroll = new ProbeScroll(context);
			scroll.addView(new View(context), new FrameLayout.LayoutParams(400, 2000));
			layout(scroll, 400, 800);
			scroll.smoothScrollTo(0, 337);
			scroll.alignEditorViewport(17);
			assertEquals(17, scroll.getScrollY());
			// Exercise the native scroller itself, not a string/source-pattern assertion.
			scroll.computeScroll(); assertEquals(17, scroll.getScrollY());
			scroll.computeScroll(); assertEquals(17, scroll.getScrollY());
		});
	}

	@Test public void manualTouchCancelsPresentationWithoutConsumingNativeScrollInput() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ProbeScroll scroll = new ProbeScroll(PostingTestContext.create()); int[] calls = {0};
			scroll.setCancelEditorMotion(() -> calls[0]++);
			MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
			try {
				scroll.dispatchTouchEvent(down); assertEquals(1, calls[0]);
				scroll.setCancelEditorMotion(null); scroll.dispatchTouchEvent(down); assertEquals(1, calls[0]);
			} finally { down.recycle(); }
		});
	}

	@Test public void keyboardKeepsOnlyOriginalPrimarySixDpBelowEditorBeforeAttachments() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String key = Preferences.KEY_POSTING_EDITOR_HEIGHT;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			Context context = PostingTestContext.create();
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
					(FrameLayout) root.findViewById(R.id.footer_container));
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				Preferences.PREFERENCES.edit().put(key, "automatic").close();
				EditText editor = root.findViewById(R.id.comment);
				LinearLayout container = root.findViewById(R.id.comment_parent);
				LinearLayout form = root.findViewById(R.id.posting_layout);
				LinearLayout attachments = root.findViewById(R.id.attachment_container);
				ScrollView scroll = root.findViewById(R.id.scroll_view);
				View actions = root.findViewById(R.id.posting_action_container);
				root.findViewById(R.id.personal_data_block).setVisibility(View.GONE);
				EditText subject = root.findViewById(R.id.subject); subject.setVisibility(View.GONE);
				appearance.setupEditor(container, editor, new LinearLayout(context));
				appearance.decorateFields(subject);
				Button send = new Button(context); int[] submissions = {0};
				send.setOnClickListener(v -> submissions[0]++);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				CompoundButton sage = root.findViewById(R.id.sage_checkbox);
				CompoundButton op = root.findViewById(R.id.original_poster_checkbox);
				sage.setChecked(true); op.setChecked(true);
				appearance.finishSetup(send, sage, op);
				View utility = (View) sage.getParent().getParent();
				editor.setText("sample draft"); editor.setSelection(3); assertTrue(editor.requestFocus());
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(400 * density), height = Math.round(420 * density), expected = -1;
				for (int count : new int[] {0, 1, 6, 0}) {
					attachments.removeAllViews();
					for (int i = 0; i < count; i++) attachments.addView(new View(context),
							new LinearLayout.LayoutParams(-1, Math.round(168 * density)));
					layout(root, width, height); appearance.updateLayout(true);
					for (int pass = 0; pass < 3; pass++) {
						layout(root, width, height);
						editor.setHeight(PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight()));
					}
					layout(root, width, height); appearance.updateLayout(true);
					root.getViewTreeObserver().dispatchOnPreDraw();
					assertSame(form, actions.getParent());
					assertEquals(form.indexOfChild(container) + 1, form.indexOfChild(actions));
					assertTrue(actions.getBottom() <= attachments.getTop());
					assertEquals(View.GONE, utility.getVisibility());
					assertTrue(sage.isChecked()); assertTrue(op.isChecked());
					Rect bounds = new Rect(); container.getDrawingRect(bounds);
					scroll.offsetDescendantRectToMyCoords(container, bounds);
					assertEquals(Math.round(6 * density), bounds.top - scroll.getScrollY());
					assertEquals(scroll.getHeight() - actions.getHeight(), bounds.bottom - scroll.getScrollY());
					Rect buttonBounds = new Rect(); send.getDrawingRect(buttonBounds);
					scroll.offsetDescendantRectToMyCoords(send, buttonBounds);
					assertEquals(Math.round(6 * density), buttonBounds.top - bounds.bottom);
					if (expected < 0) expected = editor.getHeight();
					assertEquals(expected, editor.getHeight());
					assertEquals("sample draft", editor.getText().toString()); assertEquals(3, editor.getSelectionEnd());
				}
				// Earlier fields stay in the form, but scroll above it rather than reducing its height.
				subject.setVisibility(View.VISIBLE); PostingFormAppearance.syncFieldVisibility(subject);
				for (int pass = 0; pass < 3; pass++) {
					layout(root, width, height); appearance.updateLayout(true);
					editor.setHeight(PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight()));
				}
				layout(root, width, height); appearance.updateLayout(true);
				root.getViewTreeObserver().dispatchOnPreDraw();
				Rect bounds = new Rect(); container.getDrawingRect(bounds);
				scroll.offsetDescendantRectToMyCoords(container, bounds);
				assertEquals(expected, editor.getHeight());
				assertEquals(Math.round(6 * density), bounds.top - scroll.getScrollY());
				assertEquals(scroll.getHeight() - actions.getHeight(), bounds.bottom - scroll.getScrollY());
				// Regression115/10: subject -> comment repeatedly grows the editor, but
				// a pending root request must not block the necessary local scroll alignment.
				for (int repeat = 0; repeat < 4; repeat++) {
					assertTrue(subject.requestFocus()); appearance.updateLayout(true);
					editor.setHeight(Math.max(1, expected - Math.round(60 * density)));
					layout(root, width, height); appearance.updateLayout(true);
					root.getViewTreeObserver().dispatchOnPreDraw();
					assertTrue(editor.requestFocus()); appearance.updateLayout(true);
					editor.setHeight(PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight()));
					for (int pass = 0; pass < 3; pass++) { layout(root, width, height); appearance.updateLayout(true); }
					root.requestLayout(); assertTrue(root.isLayoutRequested());
					root.getViewTreeObserver().dispatchOnPreDraw();
					Rect actionBounds = new Rect(); actions.getDrawingRect(actionBounds);
					scroll.offsetDescendantRectToMyCoords(actions, actionBounds);
					assertEquals(scroll.getHeight(), actionBounds.bottom - scroll.getScrollY());
					container.getDrawingRect(bounds); scroll.offsetDescendantRectToMyCoords(container, bounds);
					assertEquals(Math.round(6 * density), bounds.top - scroll.getScrollY());
					assertEquals(expected, editor.getHeight()); assertEquals(0, submissions[0]);
					// Once aligned, a user's scroll is not undone by another pre-draw.
					int manual = Math.max(0, scroll.getScrollY() - Math.round(12 * density));
					scroll.scrollTo(0, manual); root.getViewTreeObserver().dispatchOnPreDraw();
					assertEquals(manual, scroll.getScrollY());
				}
				appearance.updateLayout(false); layout(root, width, height);
				assertEquals(View.VISIBLE, utility.getVisibility());
				assertTrue(sage.isChecked()); assertTrue(op.isChecked());
				assertEquals(Math.round(16 * density), form.getPaddingTop());
				assertSame(scroll.getParent(), actions.getParent());
				assertEquals(0, PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight()));
				Preferences.PREFERENCES.edit().put(key, "fixed").close();
				appearance.updateLayout(true);
				assertEquals(0, PostingFormAppearance.keyboardEditorInputHeight(editor, scroll.getHeight()));
				assertSame(form, actions.getParent());
				assertEquals(View.GONE, utility.getVisibility()); assertEquals(0, submissions[0]);
			} finally {
				appearance.close();
				if (previous instanceof String) Preferences.PREFERENCES.edit().put(key, (String) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
			}
		});
	}
}
