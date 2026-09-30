package com.mishiranu.dashchan.ui.navigator.page;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;

/**
 * The existing ListPage contract, deliberately independent of the Fragment's resumed state.
 * A paused page with a living view remains STARTED so task results can populate that view.
 * Destroy is terminal. The registry still belongs to ListPage, not to this controller.
 */
final class PageLifecycle {
	final LifecycleRegistry registry;
	private boolean destroying;

	PageLifecycle(LifecycleRegistry registry) {
		this.registry = registry;
	}

	void start() {
		if (registry.getCurrentState() == Lifecycle.State.INITIALIZED) {
			registry.setCurrentState(Lifecycle.State.STARTED);
		}
	}

	boolean isRunning() {
		Lifecycle.State state = registry.getCurrentState();
		return state == Lifecycle.State.STARTED || state == Lifecycle.State.RESUMED;
	}

	void resume(Runnable onResume) {
		if (!destroying && registry.getCurrentState() == Lifecycle.State.STARTED) {
			registry.setCurrentState(Lifecycle.State.RESUMED);
			// An observer can navigate away synchronously while the registry dispatches.
			if (registry.getCurrentState() == Lifecycle.State.RESUMED) onResume.run();
		}
	}

	void pause(Runnable onPause) {
		if (!destroying && registry.getCurrentState() == Lifecycle.State.RESUMED) {
			registry.setCurrentState(Lifecycle.State.STARTED);
			if (registry.getCurrentState() == Lifecycle.State.STARTED) onPause.run();
		}
	}

	void destroy(Runnable onPause, Runnable onDestroy) {
		if (destroying || !isRunning()) return;
		destroying = true;
		try {
			if (registry.getCurrentState() == Lifecycle.State.RESUMED) {
				registry.setCurrentState(Lifecycle.State.STARTED);
				onPause.run();
			}
			registry.setCurrentState(Lifecycle.State.DESTROYED);
			onDestroy.run();
		} finally {
			destroying = false;
		}
	}
}
