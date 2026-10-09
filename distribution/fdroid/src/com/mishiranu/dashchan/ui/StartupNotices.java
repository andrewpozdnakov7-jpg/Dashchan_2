package com.mishiranu.dashchan.ui;

import java.util.function.BooleanSupplier;

/** Distribution bridge only; contains no announcement, state or resources. */
public final class StartupNotices {
	private StartupNotices() {}
	public static void install(MainActivity activity, BooleanSupplier ready) {}
}
