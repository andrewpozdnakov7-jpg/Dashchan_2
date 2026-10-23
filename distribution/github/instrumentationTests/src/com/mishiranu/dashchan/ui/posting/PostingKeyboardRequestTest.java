package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;

import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.PostingGestureTestActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises request ownership; the injected action does not claim to test Gboard/IME rendering. */
@RunWith(AndroidJUnit4.class)
public class PostingKeyboardRequestTest {
	private static final class Fixture implements AutoCloseable {
		final ActivityScenario<PostingGestureTestActivity> scenario =
				ActivityScenario.launch(PostingGestureTestActivity.class);
		final AtomicInteger calls = new AtomicInteger();
		final AtomicBoolean current = new AtomicBoolean(true);
		final CountDownLatch shown = new CountDownLatch(1);
		LinearLayout root;
		EditText editor, other;
		PostingKeyboardRequest request;

		Fixture() {
			scenario.onActivity(activity -> {
				root = new LinearLayout(activity);
				root.setOrientation(LinearLayout.VERTICAL);
				editor = new EditText(activity);
				other = new EditText(activity);
				root.addView(editor, new LinearLayout.LayoutParams(-1, 100));
				root.addView(other, new LinearLayout.LayoutParams(-1, 100));
				activity.setContentView(root);
				// Hold the request across the same ownership marker used by screen transitions.
				root.setTag(R.id.screen_motion_owner, new Object());
				assertTrue(editor.requestFocus());
				request = new PostingKeyboardRequest(root, editor, current::get, () -> {
					calls.incrementAndGet(); shown.countDown();
				});
				request.start();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
		}

		void finishTransition() {
			scenario.onActivity(activity -> {
				root.setTag(R.id.screen_motion_owner, null);
				root.postInvalidateOnAnimation();
			});
		}

		@Override public void close() {
			scenario.onActivity(activity -> request.cancel("test_finished"));
			scenario.close();
		}
	}

	@Test public void waitsForTransitionAndRequestsOnlyOnce() throws Exception {
		try (Fixture f = new Fixture()) {
			assertEquals(0, f.calls.get());
			f.finishTransition();
			assertTrue(f.shown.await(5, TimeUnit.SECONDS));
			f.scenario.onActivity(activity -> {
				f.request.start();
				f.root.getViewTreeObserver().dispatchOnPreDraw();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, f.calls.get());
		}
	}

	@Test public void cancelledOpeningCannotShowOnReturn() {
		try (Fixture f = new Fixture()) {
			f.scenario.onActivity(activity -> { f.request.cancel("paused"); f.request.start(); });
			f.finishTransition();
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(0, f.calls.get());
		}
	}

	@Test public void staleViewCannotRequestKeyboard() {
		try (Fixture f = new Fixture()) {
			f.current.set(false);
			f.finishTransition();
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(0, f.calls.get());
		}
	}

	@Test public void selectingAnotherFieldCancelsInsteadOfStealingFocusBack() {
		try (Fixture f = new Fixture()) {
			f.scenario.onActivity(activity -> {
				assertTrue(f.other.requestFocus());
				assertTrue(f.editor.requestFocus());
			});
			f.finishTransition();
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(0, f.calls.get());
		}
	}
}
