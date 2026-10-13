package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared view fixtures. They do not send posts, solve captchas or access drafts. */
@RunWith(AndroidJUnit4.class)
public class PostingFormAppearanceTest {
	@Test public void wrappingPreservesEditorsWatchersSelectionAndVisibility() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			EditText input = root.findViewById(R.id.comment);
			EditText subject = root.findViewById(R.id.subject);
			input.setText("sample draft"); input.setSelection(2, 5);
			int[] calls = {0};
			input.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) { calls[0]++; }
				@Override public void afterTextChanged(Editable s) {}
			});
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				LinearLayout formatting = new LinearLayout(context);
				appearance.setupEditor(root.findViewById(R.id.comment_parent), input, formatting);
				appearance.decorateFields(subject);
				assertSame(input, root.findViewById(R.id.comment));
				assertEquals("sample draft", input.getText().toString());
				assertEquals(2, input.getSelectionStart()); assertEquals(5, input.getSelectionEnd());
				assertEquals(0, calls[0]);
				assertTrue(formatting.getParent() instanceof HorizontalScrollView);
				assertNull(input.getBackground());
				subject.setVisibility(View.GONE); PostingFormAppearance.syncFieldVisibility(subject);
				assertEquals(View.GONE, ((View) subject.getParent()).getVisibility());
				subject.setVisibility(View.VISIBLE); PostingFormAppearance.syncFieldVisibility(subject);
				assertEquals(View.VISIBLE, ((View) subject.getParent()).getVisibility());
				assertTrue(PostingFormAppearance.fieldError(subject, true));
				assertNull(subject.getBackground());
				input.getText().append("!"); assertEquals(1, calls[0]);
			} finally { appearance.close(); }
		});
	}

	@Test public void toolbarRestoreKeepsItsOriginalBackdropAndLatestTitle() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			Toolbar toolbar = new Toolbar(context); ColorDrawable original = new ColorDrawable(Color.RED);
			toolbar.setBackground(original); toolbar.setElevation(7); toolbar.setTitle("Before");
			try {
				appearance.activateToolbar(toolbar); toolbar.setTitle("After"); appearance.deactivateToolbar();
				assertSame(original, toolbar.getBackground()); assertEquals(7, toolbar.getElevation(), 0);
				assertEquals("After", toolbar.getTitle());
			} finally { appearance.close(); }
		});
	}

	@Test public void stylingCommandsRetainsTheirExistingClickAction() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				Button button = new Button(context); int[] clicks = {0}; button.setOnClickListener(v -> clicks[0]++);
				appearance.styleCommand(button); button.performClick(); assertEquals(1, clicks[0]);
				assertTrue(button.getMinimumHeight() >= Math.round(44 * context.getResources().getDisplayMetrics().density));
			} finally { appearance.close(); }
		});
	}

	@Test public void allInputHintsStayInsideTheirEditorsWithoutExtraLabelRows() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
					(FrameLayout) root.findViewById(R.id.footer_container));
			int[] ids = {R.id.name, R.id.email, R.id.password, R.id.subject, R.id.captcha_input};
			EditText[] inputs = new EditText[ids.length];
			String[] hints = new String[ids.length];
			for (int i = 0; i < ids.length; i++) {
				inputs[i] = root.findViewById(ids[i]); hints[i] = inputs[i].getHint().toString();
			}
			EditText comment = root.findViewById(R.id.comment);
			String commentHint = comment.getHint().toString();
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), comment, new LinearLayout(context));
				appearance.decorateFields(inputs);
				assertEquals(2, ((ViewGroup) comment.getParent()).getChildCount()); // editor and formatting only
				assertEquals(commentHint, comment.getHint().toString());
				for (int i = 0; i < inputs.length; i++) {
					EditText input = inputs[i];
					assertSame(input, ((ViewGroup) input.getParent()).getChildAt(0));
					assertEquals(1, ((ViewGroup) input.getParent()).getChildCount());
					assertEquals(hints[i], input.getHint().toString());
					input.setText("sample"); input.setSelection(3);
					PostingFormAppearance.fieldError(input, true);
					assertEquals("sample", input.getText().toString()); assertEquals(3, input.getSelectionStart());
					assertEquals(hints[i], input.getHint().toString());
					input.getText().clear();
					PostingFormAppearance.fieldError(input, false);
					assertEquals(hints[i], input.getHint().toString());
				}
			} finally { appearance.close(); }
		});
	}

	private static final class SwipeParent extends FrameLayout {
		private final int slop;
		private float startX, startY;
		long downTime;
		boolean disallowed;
		int intercepted;
		SwipeParent(Context context) { super(context); slop = ViewConfiguration.get(context).getScaledTouchSlop(); }
		@Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
			disallowed = disallow;
			super.requestDisallowInterceptTouchEvent(disallow);
		}
		@Override public boolean onInterceptTouchEvent(MotionEvent event) {
			if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
				startX = event.getX(); startY = event.getY();
			} else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
				float dx = event.getX() - startX, dy = event.getY() - startY;
				if (dx > slop && dx > Math.abs(dy)) { intercepted++; return true; }
			}
			return false;
		}
	}

	private static void event(SwipeParent parent, long time, int action, float x, float y) {
		if (action == MotionEvent.ACTION_DOWN) parent.downTime = time;
		MotionEvent event = MotionEvent.obtain(parent.downTime, time, action, x, y, 0);
		try { parent.dispatchTouchEvent(event); } finally { event.recycle(); }
	}

	@Test public void formattingCanReverseFromTheRightEndWithoutOpeningItsSwipeParent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			SwipeParent parent = new SwipeParent(context);
			PostingFormAppearance.FormattingScrollView scroll = new PostingFormAppearance.FormattingScrollView(context);
			LinearLayout commands = new LinearLayout(context);
			commands.setOrientation(LinearLayout.HORIZONTAL);
			int[] clicks = {0};
			for (int i = 0; i < 10; i++) {
				Button button = new Button(context);
				button.setOnClickListener(v -> clicks[0]++);
				commands.addView(button, new LinearLayout.LayoutParams(100, 120));
			}
			scroll.addView(commands, new FrameLayout.LayoutParams(1000, 120));
			parent.addView(scroll, new FrameLayout.LayoutParams(300, 120));
			parent.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
			parent.layout(0, 0, 300, 200);
			scroll.scrollTo(700, 0);
			int before = scroll.getScrollX();
			event(parent, 100, MotionEvent.ACTION_DOWN, 50, 60);
			assertTrue(parent.disallowed);
			for (int i = 1; i <= 5; i++) event(parent, 100 + 100 * i, MotionEvent.ACTION_MOVE, 50 + 40 * i, 60);
			assertTrue(scroll.getScrollX() < before);
			event(parent, 700, MotionEvent.ACTION_UP, 250, 60);
			assertEquals(0, parent.intercepted); assertFalse(parent.disallowed); assertEquals(0, clicks[0]);
			// A tap still invokes the original button, rather than being consumed by gesture arbitration.
			scroll.scrollTo(0, 0);
			event(parent, 800, MotionEvent.ACTION_DOWN, 50, 60);
			event(parent, 850, MotionEvent.ACTION_UP, 50, 60);
			assertEquals(1, clicks[0]); assertFalse(parent.disallowed);
			// A vertical drag yields to the enclosing form; CANCEL also releases interception.
			event(parent, 900, MotionEvent.ACTION_DOWN, 150, 60);
			event(parent, 1000, MotionEvent.ACTION_MOVE, 150, 110);
			assertFalse(parent.disallowed);
			event(parent, 1100, MotionEvent.ACTION_CANCEL, 150, 110);
			assertFalse(parent.disallowed);
		});
	}

	@Test public void parentToolbarShadowAndBackdropAreRestoredAfterLeavingTheForm() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			LinearLayout parent = new LinearLayout(context); parent.setId(R.id.toolbar_layout);
			ColorDrawable original = new ColorDrawable(Color.BLUE);
			parent.setBackground(original); parent.setElevation(11);
			Toolbar toolbar = new Toolbar(context); parent.addView(toolbar);
			try {
				appearance.activateToolbar(toolbar);
				assertEquals(0, parent.getElevation(), 0);
				appearance.deactivateToolbar();
				assertSame(original, parent.getBackground()); assertEquals(11, parent.getElevation(), 0);
			} finally { appearance.close(); }
		});
	}
}
