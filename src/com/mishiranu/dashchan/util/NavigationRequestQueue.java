package com.mishiranu.dashchan.util;

import java.util.ArrayDeque;

/** Main-thread gate for content navigation; never executes FragmentManager operations itself. */
public final class NavigationRequestQueue {
	private final ArrayDeque<Runnable> requests = new ArrayDeque<>();
	private boolean pending, draining, closed;
	private int generation;

	public boolean defer(Runnable request, boolean stateSaved) {
		if (closed) return true;
		if (pending || stateSaved || !draining && !requests.isEmpty()) {
			requests.addLast(request);
			return true;
		}
		return false;
	}

	public int begin() {
		if (closed || pending) throw new IllegalStateException("Content navigation already pending or closed");
		pending = true;
		return ++generation;
	}

	public boolean complete(int ticket) {
		if (closed || !pending || ticket != generation) return false;
		pending = false;
		return true;
	}

	public boolean isPending() { return pending; }
	public boolean hasRequests() { return !requests.isEmpty(); }

	/** The host calls this from the next main-loop turn, after FragmentManager exits its callbacks. */
	public void drainOne(boolean stateSaved) {
		if (closed || pending || draining || stateSaved || requests.isEmpty()) return;
		Runnable request = requests.removeFirst();
		draining = true;
		try {
			request.run();
		} finally {
			draining = false;
		}
	}

	public void close() {
		closed = true;
		pending = false;
		generation++;
		requests.clear();
	}
}
