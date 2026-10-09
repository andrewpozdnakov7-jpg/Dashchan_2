package com.mishiranu.dashchan.ui.gallery;

import android.view.View;
import java.util.function.IntConsumer;

/** Experimental menu is unavailable; VideoUnit retains the platform popup. */
final class PlayerSpeedMenu {
	static PlayerSpeedMenu create(View anchor, View controlsPanel, int[] speeds, int current,
			boolean custom, IntConsumer select) {
		return null;
	}
	void setOnDismissListener(Runnable listener) {}
	boolean show() { return false; }
	void dismiss() {}
}
