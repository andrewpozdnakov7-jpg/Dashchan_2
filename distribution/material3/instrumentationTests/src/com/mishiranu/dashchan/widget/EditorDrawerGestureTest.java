package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.SharedPreferences;
import org.junit.Test;

/** Real dispatch through drawer -> outer scroller -> editor. No IME or ActionMode is required. */
public class EditorDrawerGestureTest {
	private static final class Fixture implements AutoCloseable {
		final CustomDrawerLayout drawer;
		final PostingScrollView scroll;
		final SafePasteEditText editor;
		long down, time;
		int moves, cancels;
		Fixture(PostingGestureTestActivity activity) {
			Context context = PostingTestContext.create(activity);
			drawer = new CustomDrawerLayout(context, null);
			scroll = new PostingScrollView(context);
			drawer.addView(scroll, new DrawerLayout.LayoutParams(-1, -1));
			View panel = new View(context); panel.setBackgroundColor(0xffcccccc);
			DrawerLayout.LayoutParams panelParams = new DrawerLayout.LayoutParams(180, -1);
			panelParams.gravity = Gravity.START; drawer.addView(panel, panelParams);
			LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
			scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
			content.addView(new View(context), new LinearLayout.LayoutParams(-1, 100));
			editor = new SafePasteEditText(context); editor.setSingleLine(false); editor.setShowSoftInputOnFocus(false);
			editor.setText("alpha beta gamma");
			content.addView(editor, new LinearLayout.LayoutParams(-1, 160));
			content.addView(new View(context), new LinearLayout.LayoutParams(-1, 1200));
			scroll.setCommentEditor(editor); scroll.setEditorViewportOwner(() -> true);
			editor.setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
					moves++; scroll.requestDisallowInterceptTouchEvent(false);
				}
				if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancels++;
				return false;
			});
			activity.setContentView(drawer);
			drawer.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); drawer.layout(0, 0, 480, 600);
		}
		void touch(int action, float x, float y) {
			if (action == MotionEvent.ACTION_DOWN) down = time = SystemClock.uptimeMillis();
			int[] origin = new int[2]; drawer.getLocationOnScreen(origin);
			MotionEvent event = MotionEvent.obtain(down, time += 32, action, x + origin[0], y + origin[1], 0);
			event.offsetLocation(-origin[0], -origin[1]);
			try { drawer.dispatchTouchEvent(event); } finally { event.recycle(); }
		}
		@Override public void close() {
			touch(MotionEvent.ACTION_CANCEL, 200, 180); scroll.setCommentEditor(null); drawer.closeDrawers();
		}
	}
	@Test public void shortEditorDragSurvivesChildReleaseBeforeSelectionMenuExists() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					assertFalse(f.editor.canScrollVertically(-1) || f.editor.canScrollVertically(1));
					f.touch(MotionEvent.ACTION_DOWN, 120, 180); assertTrue(f.drawer.isEditorGestureOwned());
					for (int x : new int[]{160, 230, 280, 180, 300}) f.touch(MotionEvent.ACTION_MOVE, x, 180);
					assertTrue(f.moves > 0); assertEquals(0, f.cancels);
					assertFalse(f.drawer.isDrawerVisible(GravityCompat.START));
					assertEquals("alpha beta gamma", f.editor.getText().toString());
					f.touch(MotionEvent.ACTION_UP, 300, 180); assertFalse(f.drawer.isEditorGestureOwned());
				}
			});
		}
	}
	@Test public void outerVerticalScrollCanStillCancelTheShortEditor() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, 200, 220);
					for (int y : new int[]{180, 130, 80, 40}) f.touch(MotionEvent.ACTION_MOVE, 200, y);
					assertTrue(f.scroll.getScrollY() > 0); assertTrue(f.cancels > 0);
					assertTrue(f.drawer.isEditorGestureOwned());
					assertFalse(f.drawer.isDrawerVisible(GravityCompat.START));
					f.touch(MotionEvent.ACTION_UP, 200, 40); assertFalse(f.drawer.isEditorGestureOwned());
				}
			});
		}
	}
	@Test public void selectionStartedAfterDownSurvivesVerticalDriftAndLeavingTheEditor() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					assertFalse(f.editor.canScrollVertically(-1) || f.editor.canScrollVertically(1));
					f.touch(MotionEvent.ACTION_DOWN, 120, 180);
					assertFalse(f.scroll.isCommentGestureOwned());
					// Native long press creates a range after DOWN, before ActionMode exists.
					f.editor.setSelection(0, 5);
					int before = f.scroll.getScrollY();
					for (int y : new int[]{185, 220, 300, 400, 100, 40}) {
						f.touch(MotionEvent.ACTION_MOVE, 200, y);
						assertTrue(f.scroll.isCommentGestureOwned());
					}
					assertEquals(0, f.cancels); assertEquals(before, f.scroll.getScrollY());
					assertFalse(f.drawer.isDrawerVisible(GravityCompat.START));
					f.touch(MotionEvent.ACTION_UP, 200, 40);
					assertFalse(f.scroll.isCommentGestureOwned()); assertFalse(f.drawer.isEditorGestureOwned());
				}
			});
		}
	}
	@Test public void cancelledSelectionReleasesTheNextOrdinaryVerticalDrag() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, 120, 180); f.editor.setSelection(0, 5);
					f.touch(MotionEvent.ACTION_MOVE, 200, 185); assertTrue(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_CANCEL, 200, 185); assertFalse(f.scroll.isCommentGestureOwned());
					f.editor.setSelection(5);
					f.touch(MotionEvent.ACTION_DOWN, 200, 220);
					int cancels = f.cancels;
					for (int y : new int[]{180, 130, 80, 40}) f.touch(MotionEvent.ACTION_MOVE, 200, y);
					assertTrue(f.scroll.getScrollY() > 0); assertTrue(f.cancels > cancels);
					assertFalse(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_UP, 200, 40);
				}
			});
		}
	}
	@Test public void existingSelectionIsProtectedFromDownAndEndsOnFocusLoss() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.editor.setSelection(0, 5); f.touch(MotionEvent.ACTION_DOWN, 120, 180);
					assertTrue(f.scroll.isCommentGestureOwned());
					f.scroll.requestDisallowInterceptTouchEvent(false);
					f.touch(MotionEvent.ACTION_MOVE, 200, 300); assertEquals(0, f.cancels);
					f.scroll.onWindowFocusChanged(false); assertFalse(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_CANCEL, 200, 300);
				}
			});
		}
	}
	@Test public void cancellingAnEditorDoesNotBlockTheNextOutsideDrawerSwipe() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, 120, 180); f.touch(MotionEvent.ACTION_CANCEL, 120, 180);
					assertFalse(f.drawer.isEditorGestureOwned());
					f.touch(MotionEvent.ACTION_DOWN, 160, 450); assertFalse(f.drawer.isEditorGestureOwned());
					for (int x : new int[]{210, 270, 340, 410}) f.touch(MotionEvent.ACTION_MOVE, x, 450);
					assertTrue(f.drawer.isDrawerVisible(GravityCompat.START));
				}
			});
		}
	}
	@Test public void savedOptOutFromOlderVersionsStillProtectsSelection() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				String legacyKey = "experimental_galaxy_m12_selection_fix";
				SharedPreferences preferences = Preferences.PREFERENCES;
				Object previous = preferences.getAll().get(legacyKey);
				preferences.edit().put(legacyKey, false).close();
				try (Fixture f = new Fixture(activity)) {
					f.touch(MotionEvent.ACTION_DOWN, 120, 180); f.editor.setSelection(0, 5);
					assertTrue(f.drawer.isEditorGestureOwned());
					for (int y : new int[]{185, 220, 300, 400}) f.touch(MotionEvent.ACTION_MOVE, 200, y);
					assertEquals(0, f.cancels); assertTrue(f.scroll.isCommentGestureOwned());
					f.touch(MotionEvent.ACTION_UP, 200, 400); assertFalse(f.scroll.isCommentGestureOwned());
				} finally {
					try (SharedPreferences.Editor editor = preferences.edit()) {
						if (previous instanceof Boolean value) editor.put(legacyKey, value);
						else editor.remove(legacyKey);
					}
				}
			});
		}
	}
}
