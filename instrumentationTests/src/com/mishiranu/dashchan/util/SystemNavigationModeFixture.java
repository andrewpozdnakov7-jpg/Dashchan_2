package com.mishiranu.dashchan.util;

import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;

/** Supplies the system navigation mode to tests that do not control Android system settings. */
public final class SystemNavigationModeFixture {
	private SystemNavigationModeFixture() {}

	public static void setMode(SystemNavigationMode.Mode mode) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			SystemNavigationMode.updateMode(mode);
		} else {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SystemNavigationMode.updateMode(mode));
		}
	}
}
