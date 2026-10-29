package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import org.junit.Test;

public class BackKeyPolicyTest {
	private static KeyEvent event(int action, int key, int flags) {
		return new KeyEvent(1, 2, action, key, 0, 0, -1, 0, flags);
	}

	@Test public void canceledAndLongPressReleasesCannotConfirmNavigation() {
		for (int flags : new int[] {KeyEvent.FLAG_CANCELED, KeyEvent.FLAG_CANCELED_LONG_PRESS,
				KeyEvent.FLAG_LONG_PRESS, KeyEvent.FLAG_CANCELED | KeyEvent.FLAG_CANCELED_LONG_PRESS}) {
			assertFalse(BackKeyPolicy.isConfirmedRelease(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, flags)));
		}
	}

	@Test public void onlyAnOrdinaryBackReleaseConfirmsAnAction() {
		assertTrue(BackKeyPolicy.isConfirmedRelease(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)));
		assertFalse(BackKeyPolicy.isConfirmedRelease(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)));
		assertFalse(BackKeyPolicy.isConfirmedRelease(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)));
	}
}
