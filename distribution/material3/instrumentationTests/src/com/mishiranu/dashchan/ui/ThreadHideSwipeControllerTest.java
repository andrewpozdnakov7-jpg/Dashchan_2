package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real row-property ownership, without adapters, hidden data, Activity or navigation. ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class ThreadHideSwipeControllerTest {
	private View row() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		View view = new TextView(context);
		view.setTag(R.id.thread_motion_key, new ThreadMotionKey("chan", "a", "123")); return view;
	}
	@Test public void cancelledReflowAndRebindingReleaseOnlyTheirOwnRowValues() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = row(); ThreadMotionKey key = (ThreadMotionKey) view.getTag(R.id.thread_motion_key);
			ThreadHideSwipeController.OwnedView motion = new ThreadHideSwipeController.OwnedView(view, key, 60, 80, true);
			motion.frame(0);
			assertEquals(60f, view.getTranslationX(), 0); assertEquals(80f, view.getTranslationY(), 0);
			assertEquals(0f, view.getAlpha(), 0);
			ThreadHideSwipeController.onBinding(view);
			assertEquals(0f, view.getTranslationX(), 0); assertEquals(0f, view.getTranslationY(), 0);
			assertEquals(1f, view.getAlpha(), 0); assertNull(view.getTag(R.id.thread_hide_motion_owner));
			view.setTag(R.id.thread_motion_key, new ThreadMotionKey("chan", "b", "123"));
			motion.frame(.5f); motion.release();
			assertEquals(0f, view.getTranslationX(), 0); assertEquals(1f, view.getAlpha(), 0);
		});
	}
	@Test public void screenTransitionOnAnAncestorDoesNotLeaveRowOffsetsBehind() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = row(); FrameLayout parent = new FrameLayout(view.getContext()); parent.addView(view);
			ThreadMotionKey key = (ThreadMotionKey) view.getTag(R.id.thread_motion_key);
			ThreadHideSwipeController.OwnedView motion = new ThreadHideSwipeController.OwnedView(view, key, 30, 90, true);
			motion.frame(0); Object screen = new Object(); parent.setTag(R.id.screen_motion_owner, screen);
			motion.release();
			assertEquals(0f, view.getTranslationY(), 0); assertEquals(1f, view.getAlpha(), 0);
			assertSame(screen, parent.getTag(R.id.screen_motion_owner));
		});
	}
	@Test public void aNewOwnerOnTheSameViewIsNotResetByTheOldReflow() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = row(); ThreadMotionKey key = (ThreadMotionKey) view.getTag(R.id.thread_motion_key);
			ThreadHideSwipeController.OwnedView motion = new ThreadHideSwipeController.OwnedView(view, key, 30, 90, true);
			motion.frame(0); Object incoming = new Object(); view.setTag(R.id.thread_motion_owner, incoming);
			view.setTranslationX(15); view.setAlpha(.7f); motion.release();
			assertEquals(15f, view.getTranslationX(), 0); assertEquals(.7f, view.getAlpha(), 0);
			assertSame(incoming, view.getTag(R.id.thread_motion_owner));
			assertNull(view.getTag(R.id.thread_hide_motion_owner));
		});
	}
}
