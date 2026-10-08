package com.mishiranu.dashchan.ui;

import android.os.Trace;
import com.mishiranu.dashchan.ui.navigator.PageItem;
import com.mishiranu.dashchan.util.NavigationRequestQueue;
import com.mishiranu.dashchan.util.PerformanceDiagnostics;
import java.util.function.Supplier;

/** Main-thread navigation state; the Activity remains the only FragmentTransaction commit owner. */
final class ContentNavigationCoordinator {
	interface Host {
		boolean isUnavailable();
		boolean isStateSaved();
		void post(Runnable work);
		void remove(Runnable work);
	}
	private final Host host;
	private final NavigationRequestQueue requests = new NavigationRequestQueue();
	private ContentFragment pendingFragment;
	private PageItem pendingPageItem;
	private int pendingTicket;
	private boolean drainPosted;
	private boolean saveSessionAfterNavigation;
	// Only active while a specific request runs; never a global "next animation".
	private int requestTransition; // Zero denotes no presentation override.
	private final Runnable drain = this::drainOne;

	ContentNavigationCoordinator(Host host) { this.host = host; }
	boolean isPending() { return requests.isPending(); }
	boolean hasRequests() { return requests.hasRequests(); }
	boolean isPendingFragment(ContentFragment fragment) { return fragment == pendingFragment; }
	PageItem pendingPageItem() { return pendingPageItem; }
	boolean shouldSaveSession() { return saveSessionAfterNavigation; }
	void setSaveSessionPending(boolean pending) { saveSessionAfterNavigation = pending; }
	int requestTransition() { return requestTransition; }
	void runWithTransition(int transition, Runnable action) {
		callWithTransition(transition, () -> { action.run(); return null; });
	}
	<T> T callWithTransition(int transition, Supplier<T> action) {
		int previous = requestTransition;
		requestTransition = transition;
		try {
			return action.get();
		} finally {
			requestTransition = previous;
		}
	}
	boolean defer(Runnable action) {
		if (host.isUnavailable()) return true;
		int transition = requestTransition;
		boolean deferred = requests.defer(() -> runWithTransition(transition, action), host.isStateSaved());
		if (deferred) scheduleDrain();
		return deferred;
	}
	void scheduleDrain() {
		if (!drainPosted && !requests.isPending() && requests.hasRequests()
				&& !host.isUnavailable() && !host.isStateSaved()) {
			drainPosted = true;
			host.post(drain);
		}
	}
	private void drainOne() {
		drainPosted = false;
		if (host.isUnavailable()) return;
		PerformanceDiagnostics.run("ContentNavigation/drain", () -> requests.drainOne(host.isStateSaved()));
		scheduleDrain();
	}
	int begin(ContentFragment fragment, PageItem pageItem) {
		int ticket = requests.begin();
		pendingFragment = fragment;
		pendingPageItem = pageItem;
		pendingTicket = ticket;
		Trace.beginAsyncSection("ContentNavigation/commit", ticket);
		return ticket;
	}
	boolean complete(int ticket) {
		if (!requests.complete(ticket)) return false;
		Trace.endAsyncSection("ContentNavigation/commit", ticket);
		pendingFragment = null;
		pendingPageItem = null;
		pendingTicket = 0;
		return true;
	}
	void close() {
		host.remove(drain);
		requests.close();
		if (pendingTicket != 0) Trace.endAsyncSection("ContentNavigation/commit", pendingTicket);
		pendingTicket = 0;
		pendingFragment = null;
		pendingPageItem = null;
	}
}
