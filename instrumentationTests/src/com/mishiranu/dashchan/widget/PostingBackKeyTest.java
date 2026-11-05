package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Legacy pre-IME event ownership, exercised independently of the device API level. */
public class PostingBackKeyTest {
	private static final class Owner implements PostingScrollView.BackCallback {
		boolean eligible = true;
		int invocations;
		@Override public boolean canHandlePostingBack(View view) { return eligible; }
		@Override public void onPostingBack(View view) { invocations++; }
	}
	private static KeyEvent event(int action, int flags) {
		return new KeyEvent(1, 2, action, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, flags);
	}
	private static PostingScrollView view() {
		return new PostingScrollView(InstrumentationRegistry.getInstrumentation().getTargetContext());
	}
	@Test public void confirmedPairNavigatesOnceAndOrphanReleaseDoesNot() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostingScrollView view = view(); Owner owner = new Owner();
			assertFalse(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), owner));
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), owner));
			assertEquals(0, owner.invocations);
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), owner));
			assertFalse(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), owner));
			assertEquals(1, owner.invocations);
		});
	}
	@Test public void canceledReleaseAndChangedOwnerCannotNavigate() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostingScrollView view = view(); Owner first = new Owner(), second = new Owner();
			for (int flags : new int[]{KeyEvent.FLAG_CANCELED, KeyEvent.FLAG_CANCELED_LONG_PRESS, KeyEvent.FLAG_LONG_PRESS}) {
				assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), first));
				assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, flags), first));
			}
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), first));
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), second));
			assertEquals(0, first.invocations); assertEquals(0, second.invocations);
		});
	}
	@Test public void lostFocusOrLocalWindowCannotCompleteTheOldPair() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostingScrollView view = view(); Owner owner = new Owner();
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), owner));
			view.onWindowFocusChanged(false);
			assertFalse(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), owner));
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), owner));
			owner.eligible = false;
			assertTrue(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_UP, 0), owner));
			assertFalse(view.dispatchPostingBackBeforeIme(event(KeyEvent.ACTION_DOWN, 0), owner));
			assertEquals(0, owner.invocations);
		});
	}
}
