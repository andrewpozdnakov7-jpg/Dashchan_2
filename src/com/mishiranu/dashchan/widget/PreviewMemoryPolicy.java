package com.mishiranu.dashchan.widget;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;

/** Registered only for a live preview owner; no process-global View/provider retention. */
@SuppressWarnings("deprecation")
public final class PreviewMemoryPolicy implements ComponentCallbacks2, AutoCloseable {
	private final Context application;
	private final Runnable clear;
	public PreviewMemoryPolicy(Context context, Runnable clear) {
		application = context.getApplicationContext(); this.clear = clear; application.registerComponentCallbacks(this);
	}
	/** Hiding UI is not memory pressure. Keep bounded previews until a reclamation request. */
	public static boolean shouldTrim(int level) {
		return level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_BACKGROUND;
	}
	@Override public void onTrimMemory(int level) { if (shouldTrim(level)) clear.run(); }
	@Override public void onLowMemory() { clear.run(); }
	@Override public void onConfigurationChanged(Configuration configuration) { clear.run(); }
	@Override public void close() { application.unregisterComponentCallbacks(this); }
}
