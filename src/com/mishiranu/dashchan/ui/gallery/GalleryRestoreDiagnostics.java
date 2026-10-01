package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import com.mishiranu.dashchan.util.PerformanceDiagnostics;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Observation only. Never waits for storage, publishes a different token, or logs a token/path/URI. */
final class GalleryRestoreDiagnostics {
	private GalleryRestoreDiagnostics() {}
	private static final LinkedHashMap<String, Save> RECENT = new LinkedHashMap<>();
	private static final AtomicInteger IDS = new AtomicInteger(), QUEUED = new AtomicInteger();
	static final class Save {
		final int id = IDS.incrementAndGet();
		final long started = PerformanceDiagnostics.now();
		final AuditDiagnostics.Scope queue = AuditDiagnostics.beginAsync("Audit/Gallery/saveQueue");
		final AuditDiagnostics.Scope durable = AuditDiagnostics.beginAsync("Audit/Gallery/requestToDurable");
		volatile String state = "queued";
		private boolean dequeued;
		Save(int count) { queue.sizes(count, QUEUED.incrementAndGet()).count(id); durable.sizes(count, -1).count(id); }
		void start() {
			dequeue("started");
			state = "writing";
		}
		private void dequeue(String result) {
			if (!dequeued) {
				dequeued = true;
				QUEUED.decrementAndGet();
				queue.result(result); queue.close();
			}
		}
		void written() { state = "durable"; durable.result("ok"); durable.close(); }
		void finish() {
			dequeue("failed");
			if (!"durable".equals(state)) { state = "failed"; durable.result("failed"); durable.close(); }
		}
	}
	static Save register(String token, int count) {
		Save save = new Save(count);
		synchronized (RECENT) {
			RECENT.put(token, save);
			if (RECENT.size() > 32) RECENT.remove(RECENT.keySet().iterator().next());
		}
		return save;
	}
	static void observe(String operation, String token) {
		Save save;
		synchronized (RECENT) { save = RECENT.get(token); }
		String state = token == null ? "absent" : save == null ? "unknown_after_restart_or_eviction" : save.state;
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin(operation).reason(state)
				.count(save != null ? save.id : 0).sizes(save != null
						? (PerformanceDiagnostics.now() - save.started) / 1000 : -1, QUEUED.get())) {
			scope.result(state);
		}
	}
}
