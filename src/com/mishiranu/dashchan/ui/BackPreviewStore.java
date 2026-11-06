package com.mishiranu.dashchan.ui;

import android.content.Context;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.PreviewMemoryPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** UI-thread-only bitmap ownership. No Activity, Fragment, View, callback or network work. */
final class BackPreviewStore implements BackPreviewBudget.Cache {
	private static BackPreviewStore process;
	static BackPreviewStore forProcess(Context context, long profileGeneration) {
		if (process == null) process = new BackPreviewStore(true, context.getApplicationContext());
		if (process.profileGeneration != profileGeneration) {
			process.clear("profile_changed");
			process.profileGeneration = profileGeneration;
		}
		return process;
	}
	final LinkedHashMap<Object, PredictiveBackController.Preview> previews = new LinkedHashMap<>(4, .75f, true);
	PredictiveBackController.Signature signature;
	final boolean retained;
	private final PreviewMemoryPolicy memoryPolicy;
	private Object owner, nearest;
	private long profileGeneration = Long.MIN_VALUE;
	BackPreviewStore() { this(false, null); }
	BackPreviewStore(boolean retained) { this(retained, null); }
	private BackPreviewStore(boolean retained, Context application) {
		this.retained = retained;
		BackPreviewBudget.registerCache(this);
		memoryPolicy = retained && application != null
				? new PreviewMemoryPolicy(application, () -> clear("process_memory_policy")) : null;
	}
	void attach(Object token) {
		if (owns(token)) return;
		owner = token; nearest = null;
		record("owner_attached");
	}
	boolean owns(Object token) { return owner == token; }
	void protect(Object token, Object key) { if (owns(token)) nearest = key; }
	void detach(Object token) {
		if (owns(token)) { owner = nearest = null; record("owner_detached"); }
	}
	@Override public boolean evictOldest() {
		if (previews.isEmpty()) return false;
		Map.Entry<Object, PredictiveBackController.Preview> eldest = null;
		for (Map.Entry<Object, PredictiveBackController.Preview> entry : previews.entrySet()) {
			if (!Objects.equals(entry.getKey(), nearest)) { eldest = entry; break; }
		}
		if (eldest == null) eldest = previews.entrySet().iterator().next();
		Object key = eldest.getKey();
		recordRemoval(key, "evict_oldest");
		PredictiveBackController.Preview removed = previews.remove(key);
		BackPreviewBudget.release(removed.image);
		if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("predictive_page evict_oldest"
				+ " removed_previous=" + Objects.equals(key, nearest) + " cached_pages=" + previews.size());
		return true;
	}
	void clear(String reason) {
		record("clear_begin reason=" + reason);
		for (Map.Entry<Object, PredictiveBackController.Preview> entry : previews.entrySet()) {
			recordRemoval(entry.getKey(), "cache_clear_" + reason);
			BackPreviewBudget.release(entry.getValue().image);
		}
		previews.clear(); signature = null;
		record("clear_end reason=" + reason);
	}
	private void record(String event) {
		if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("predictive_store " + event
				+ " retained=" + retained + " cached_pages=" + previews.size() + " owner_present=" + (owner != null)
				+ " shared_used_bytes=" + BackPreviewBudget.usedBytes());
	}
	void close() {
		clear("store_closed");
		if (memoryPolicy != null) memoryPolicy.close();
		BackPreviewBudget.unregisterCache(this);
	}
	static void recordRemoval(Object key, String reason) {
		if (!VideoDiagnostics.isExtendedRecording()) return;
		VideoDiagnostics.recordBackPreviewEvent(key, reason);
		VideoDiagnostics.recordUi("predictive_page snapshot_removed reason=" + reason + " "
				+ VideoDiagnostics.backPreviewHistory(key));
	}
}
