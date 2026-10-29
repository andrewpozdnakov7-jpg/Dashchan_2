package com.mishiranu.dashchan.widget;

import android.view.KeyEvent;

/** A canceled legacy key release must never perform navigation or a local Back action. */
public final class BackKeyPolicy {
	private BackKeyPolicy() {}

	public static boolean isConfirmedRelease(KeyEvent event) {
		return event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP &&
				!event.isCanceled() && !event.isLongPress() &&
				(event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0;
	}
}
