package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;

/** Prepared real widget/dispatch fixtures. No physical keyboard or phone verification is implied. */
public class PostingCommentScrollGestureTest {
	private static final class Form extends PostingScrollView {
		boolean ime = true;
		Form(Context context) { super(context); }
		@Override boolean isCommentImeVisible() { return ime; }
	}
	private static final class Fixture implements AutoCloseable {
		final Form scroll;
		final LinearLayout content;
		final EditText editor;
		int editorDowns;
		final long downTime = SystemClock.uptimeMillis();
		long time = downTime;
		Fixture(PostingGestureTestActivity activity) {
			Context context = PostingTestContext.create(activity);
			scroll = new Form(context); content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
			scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
			content.addView(new View(context), new LinearLayout.LayoutParams(-1, 120));
			editor = new EditText(context); editor.setSingleLine(false); editor.setShowSoftInputOnFocus(false);
			content.addView(editor, new LinearLayout.LayoutParams(-1, 180));
			content.addView(new View(context), new LinearLayout.LayoutParams(-1, 1200));
			StringBuilder text = new StringBuilder();
			for (int i = 0; i < 80; i++) text.append("Line ").append(i).append('\n');
			editor.setText(text); editor.setSelection(0);
			scroll.setEditorViewportOwner(() -> true); // Isolate interception from separate cursor-alignment policy.
			scroll.setCommentEditor(editor);
			editor.setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) editorDowns++;
				// Exercise a native child's attempted edge release, without consuming its touch.
				if (event.getActionMasked() == MotionEvent.ACTION_MOVE) scroll.requestDisallowInterceptTouchEvent(false);
				return false;
			});
			activity.setContentView(scroll); layout(); scroll.scrollTo(0, 50);
		}
		void layout() {
			scroll.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); scroll.layout(0, 0, 480, 600);
		}
		void touch(int action, boolean inside, float offsetY) {
			int[] at = new int[2], origin = new int[2]; editor.getLocationOnScreen(at); scroll.getLocationOnScreen(origin);
			float x = at[0] + editor.getWidth() / 2f;
			float y = inside ? at[1] + editor.getHeight() / 2f + offsetY : origin[1] + 20;
			MotionEvent event = MotionEvent.obtain(downTime, time += 16, action, x, y, 0);
			event.offsetLocation(-origin[0], -origin[1]);
			try { scroll.dispatchTouchEvent(event); } finally { event.recycle(); }
		}
		void pointerUp() {
			int[] at = new int[2], origin = new int[2]; editor.getLocationOnScreen(at); scroll.getLocationOnScreen(origin);
			MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
			MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
			for (int i = 0; i < 2; i++) {
				properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i;
				properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
				coords[i] = new MotionEvent.PointerCoords(); coords[i].x = at[0] + 30 + i * 20; coords[i].y = at[1] + 40;
				coords[i].pressure = 1; coords[i].size = 1;
			}
			for (int action : new int[]{MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP}) {
				MotionEvent event = MotionEvent.obtain(downTime, time += 16, action | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
						2, properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
				event.offsetLocation(-origin[0], -origin[1]);
				try { scroll.dispatchTouchEvent(event); } finally { event.recycle(); }
			}
		}
		@Override public void close() { touch(MotionEvent.ACTION_CANCEL, true, 0); scroll.setCommentEditor(null); }
	}

	@Test public void edgePauseAndReversalKeepTheEntireGestureInsideLongText() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					assertTrue(f.editor.canScrollVertically(1)); int outerY = f.scroll.getScrollY();
					f.touch(MotionEvent.ACTION_DOWN, true, 0); assertTrue(f.scroll.isCommentGestureOwned());
					for (float offset : new float[]{0, -30, -30, 20, -50}) {
						f.editor.scrollTo(0, 0); f.touch(MotionEvent.ACTION_MOVE, true, offset);
						assertTrue(f.scroll.isCommentGestureOwned()); assertEquals(outerY, f.scroll.getScrollY());
					}
					f.pointerUp(); assertTrue(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_UP, true, 0); assertFalse(f.scroll.isCommentGestureOwned());
				}
			});
		}
	}

	@Test public void bothEndsOfLongTextStillQualifyForANewGesture() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					int bottom = f.editor.getLayout().getHeight() - f.editor.getHeight()
							+ f.editor.getTotalPaddingTop() + f.editor.getTotalPaddingBottom();
					for (int y : new int[]{0, bottom}) {
						f.editor.scrollTo(0, y); f.touch(MotionEvent.ACTION_DOWN, true, 0);
						assertTrue(f.scroll.isCommentGestureOwned()); f.touch(MotionEvent.ACTION_CANCEL, true, 0);
					}
				}
			});
		}
	}

	@Test public void shortTextClosedKeyboardAndOutsideTouchesKeepTheOuterScroller() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, false, 0); assertFalse(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_CANCEL, false, 0);
					f.scroll.ime = false; f.touch(MotionEvent.ACTION_DOWN, true, 0); assertFalse(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_CANCEL, true, 0); f.scroll.ime = true;
					f.editor.setText("short"); f.editor.scrollTo(0, 0); f.layout();
					assertFalse(f.editor.canScrollVertically(-1) || f.editor.canScrollVertically(1));
					f.touch(MotionEvent.ACTION_DOWN, true, 0); assertFalse(f.scroll.isCommentGestureOwned());
				}
			});
		}
	}

	@Test public void anOuterFlingCannotStealTheNewCommentDown() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					int before = f.scroll.getScrollY(); f.scroll.fling(5000);
					f.touch(MotionEvent.ACTION_DOWN, true, 0); assertTrue(f.scroll.isCommentGestureOwned());
					assertEquals(1, f.editorDowns); f.scroll.computeScroll(); assertEquals(before, f.scroll.getScrollY());
				}
			});
		}
	}

	@Test public void cancellationAndOwnerRemovalReleaseTheNextOutsideGesture() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, true, 0); f.touch(MotionEvent.ACTION_CANCEL, true, 0);
					assertFalse(f.scroll.isCommentGestureOwned()); f.touch(MotionEvent.ACTION_DOWN, true, 0);
					assertTrue(f.scroll.isCommentGestureOwned()); f.content.removeView(f.editor);
					assertFalse(f.scroll.isCommentGestureOwned()); f.scroll.setCommentEditor(null);
					f.touch(MotionEvent.ACTION_DOWN, false, 0); assertFalse(f.scroll.isCommentGestureOwned());
				}
			});
		}
	}

	@Test public void movingActionTapKeepsPriorityOverCommentOwnership() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					int[] cancelled = {0}; f.scroll.setCancelEditorMotion(() -> cancelled[0]++);
					f.scroll.setEditorMotionTouchHandler(event -> true); f.touch(MotionEvent.ACTION_DOWN, true, 0);
					assertFalse(f.scroll.isCommentGestureOwned()); assertEquals(0, f.editorDowns); assertEquals(0, cancelled[0]);
				}
			});
		}
	}
}
