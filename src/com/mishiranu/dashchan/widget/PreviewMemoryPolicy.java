package com.mishiranu.dashchan.widget;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One process callback and one decision for all preview owners. UI thread only. */
@SuppressWarnings("deprecation")
public final class PreviewMemoryPolicy implements AutoCloseable {
	private static Dispatcher process;
	private final Dispatcher dispatcher;
	private final Consumer<String> clear;
	private final BackPreviewBudget.Cache cache;
	private boolean closed;

	public PreviewMemoryPolicy(Context context, Consumer<String> clear) { this(context, clear, null); }
	public PreviewMemoryPolicy(Context context, Consumer<String> clear, BackPreviewBudget.Cache cache) {
		this(dispatcher(context), clear, cache);
	}
	PreviewMemoryPolicy(Dispatcher dispatcher, Consumer<String> clear, BackPreviewBudget.Cache cache) {
		this.dispatcher = dispatcher; this.clear = clear; this.cache = cache;
		dispatcher.owners.put(this, Boolean.TRUE);
		if (cache != null) BackPreviewBudget.registerCache(cache);
	}
	private static Dispatcher dispatcher(Context context) {
		if (process == null) {
			Context application = context.getApplicationContext();
			process = new Dispatcher(application, () -> isMemoryLow(application), true);
		}
		return process;
	}
	public static boolean isMemoryLow(Context context) {
		ActivityManager manager = context.getSystemService(ActivityManager.class);
		if (manager == null) return false;
		ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
		manager.getMemoryInfo(info);
		return info.lowMemory;
	}
	/** Background state is not proof of pressure. Old explicit pressure levels remain actionable. */
	static boolean shouldTrim(int level, boolean lowMemory) {
		return lowMemory || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
				|| level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
				&& level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
	}
	static boolean presentationChanged(Configuration before, Configuration after) {
		int visual = ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_UI_MODE
				| ActivityInfo.CONFIG_ORIENTATION | ActivityInfo.CONFIG_SCREEN_SIZE
				| ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE | ActivityInfo.CONFIG_SCREEN_LAYOUT
				| ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION | ActivityInfo.CONFIG_COLOR_MODE
				| ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT | ActivityInfo.CONFIG_ASSETS_PATHS
				| ActivityInfo.CONFIG_GRAMMATICAL_GENDER;
		return (before.diff(after) & visual) != 0;
	}
	/** Capture failure and confirmed low memory use the same cache-only reclamation path. */
	public static void reclaim(Context context, String reason) { dispatcher(context).reclaim(reason); }
	static void record(String event) {
		if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("predictive_memory " + event);
	}
	static final class Dispatcher implements ComponentCallbacks2 {
		final WeakHashMap<PreviewMemoryPolicy, Boolean> owners = new WeakHashMap<>();
		private final BooleanSupplier lowMemory;
		private Configuration configuration;
		private boolean reclaiming;
		Dispatcher(Context application, BooleanSupplier lowMemory, boolean register) {
			this.lowMemory = lowMemory;
			configuration = new Configuration(application.getResources().getConfiguration());
			if (register) application.registerComponentCallbacks(this);
		}
		@Override public void onTrimMemory(int level) {
			boolean low = lowMemory.getAsBoolean();
			String reason = "trim_memory level=" + level + " low_memory=" + low;
			if (shouldTrim(level, low)) reclaim(reason);
			else record("retained reason=" + reason + " used_bytes=" + BackPreviewBudget.usedBytes());
		}
		@Override public void onLowMemory() { reclaim("on_low_memory"); }
		@Override public void onConfigurationChanged(Configuration next) {
			boolean changed = presentationChanged(configuration, next);
			configuration = new Configuration(next);
			if (changed) reclaim("configuration_presentation_changed");
			else record("retained reason=configuration_compatible used_bytes=" + BackPreviewBudget.usedBytes());
		}
		void reclaim(String reason) {
			if (reclaiming) return;
			reclaiming = true;
			long before = BackPreviewBudget.usedBytes();
			try {
				record("clear_begin reason=" + reason + " used_bytes=" + before);
				for (PreviewMemoryPolicy owner : new ArrayList<>(owners.keySet())) {
					if (!owner.closed) owner.clear.accept(reason);
				}
				// Also release standalone ledger caches. Active renderers retain their own leases.
				BackPreviewBudget.trimCaches(reason);
				record("clear_end reason=" + reason + " released_bytes="
						+ (before - BackPreviewBudget.usedBytes()) + " used_bytes=" + BackPreviewBudget.usedBytes());
			} finally { reclaiming = false; }
		}
	}
	@Override public void close() {
		if (closed) return;
		closed = true; dispatcher.owners.remove(this);
		if (cache != null) BackPreviewBudget.unregisterCache(cache);
	}
}
