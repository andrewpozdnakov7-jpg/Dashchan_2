package com.mishiranu.dashchan.ui;

import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import java.util.ArrayList;

/** Activity-scoped drawer transition orchestration, with no Views or FragmentManager. */
final class DrawerNavigationCoordinator {
	interface Host {
		boolean isWide();
		boolean isDrawerVisible();
		void cancelPrewarm();
		void closeDrawer();
		void postAfterClose(Runnable work);
	}
	private final Host host;
	private Runnable pendingDrawerNavigation;
	private boolean performingDrawerNavigation;
	private boolean drawerNavigationTransitionRunning;
	private final ArrayList<Runnable> deferredDrawerUiWork = new ArrayList<>();

	DrawerNavigationCoordinator(Host host) { this.host = host; }
	boolean isTransitionRunning() { return drawerNavigationTransitionRunning; }
	boolean isPerforming() { return performingDrawerNavigation; }

	boolean schedule(Runnable navigation) {
		if (!performingDrawerNavigation && !host.isWide() && host.isDrawerVisible()) {
			// Start both transitions from the click event. Posting the navigation to the next animation frame puts
			// fragment creation directly into the drawer frame and causes visible stalls on 120 Hz devices.
			pendingDrawerNavigation = navigation;
			drawerNavigationTransitionRunning = true;
			host.cancelPrewarm();
			Trace.beginSection("DrawerNavigation/transition_start");
			Trace.endSection();
			Log.d("DrawerNavPerf", "event=transition_start");
			host.closeDrawer();
			runPending();
			return true;
		}
		return false;
	}

	void runPending() {
		Runnable navigation = pendingDrawerNavigation;
		pendingDrawerNavigation = null;
		if (navigation != null) {
			Trace.beginSection("MainActivity#drawerNavigation");
			long start = SystemClock.elapsedRealtime();
			performingDrawerNavigation = true;
			try {
				navigation.run();
			} finally {
				performingDrawerNavigation = false;
				Trace.endSection();
				Log.d("DrawerNavPerf", "event=navigation_end elapsed_ms="
						+ (SystemClock.elapsedRealtime() - start));
			}
		}
	}

	boolean deferUiWork(Runnable runnable) {
		if (!drawerNavigationTransitionRunning || host.isWide()) {
			return false;
		}
		deferredDrawerUiWork.add(runnable);
		Trace.beginSection("DrawerNavigation/work_deferred");
		Trace.endSection();
		Log.d("DrawerNavPerf", "event=work_deferred count=" + deferredDrawerUiWork.size());
		return true;
	}

	void finishTransition() {
		drawerNavigationTransitionRunning = false;
		if (deferredDrawerUiWork.isEmpty()) {
			Log.d("DrawerNavPerf", "event=drawer_closed deferred_count=0");
			return;
		}
		ArrayList<Runnable> work = new ArrayList<>(deferredDrawerUiWork);
		deferredDrawerUiWork.clear();
		Log.d("DrawerNavPerf", "event=drawer_closed deferred_count=" + work.size());
		// Do not use postOnAnimation here: a Choreographer callback makes the deferred adapter update
		// part of the first frame after the drawer closes. Run it as a regular main-loop message instead,
		// so the final drawer frame is committed before heavier destination work begins.
		host.postAfterClose(() -> {
			Trace.beginSection("DrawerNavigation/deferred_work_after_close");
			long start = SystemClock.elapsedRealtime();
			try {
				for (Runnable runnable : work) {
					runnable.run();
				}
			} finally {
				Trace.endSection();
				Log.d("DrawerNavPerf", "event=deferred_work_end count=" + work.size()
						+ " elapsed_ms=" + (SystemClock.elapsedRealtime() - start));
			}
		});
	}
}
