package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.fragment.app.FragmentTransaction;
import com.mishiranu.dashchan.ui.navigator.PageItem;
import com.mishiranu.dashchan.ui.preference.AboutFragment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NavigationCoordinatorTest {
	private static final class ContentHost implements ContentNavigationCoordinator.Host {
		boolean unavailable, stateSaved;
		final ArrayDeque<Runnable> posted = new ArrayDeque<>();
		@Override public boolean isUnavailable() { return unavailable; }
		@Override public boolean isStateSaved() { return stateSaved; }
		@Override public void post(Runnable work) { posted.addLast(work); }
		@Override public void remove(Runnable work) { posted.remove(work); }
		void drain() { posted.removeFirst().run(); }
	}
	@Test public void queuedNavigationWaitsForExactTicketAndUnsavedState() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentHost host = new ContentHost();
			ContentNavigationCoordinator coordinator = new ContentNavigationCoordinator(host);
			AboutFragment fragment = new AboutFragment(); PageItem item = new PageItem();
			int ticket = coordinator.begin(fragment, item);
			assertTrue(coordinator.isPendingFragment(fragment));
			assertSame(item, coordinator.pendingPageItem());
			ArrayList<Integer> actions = new ArrayList<>();
			assertTrue(coordinator.defer(() -> actions.add(1)));
			assertTrue(coordinator.defer(() -> actions.add(2)));
			assertTrue(host.posted.isEmpty());
			assertFalse(coordinator.complete(ticket + 1));
			assertTrue(coordinator.complete(ticket));
			assertNull(coordinator.pendingPageItem());
			host.stateSaved = true; coordinator.scheduleDrain(); assertTrue(host.posted.isEmpty());
			host.stateSaved = false; coordinator.scheduleDrain(); coordinator.scheduleDrain();
			assertEquals(1, host.posted.size()); host.drain(); host.drain();
			assertEquals(Arrays.asList(1, 2), actions);
		});
	}
	@Test public void destroyClearsPendingDrainAndRejectsStaleCommit() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentHost host = new ContentHost();
			ContentNavigationCoordinator coordinator = new ContentNavigationCoordinator(host);
			host.stateSaved = true;
			assertTrue(coordinator.defer(() -> fail("After destruction")));
			host.stateSaved = false; coordinator.scheduleDrain();
			coordinator.close(); assertTrue(host.posted.isEmpty());
			assertTrue(coordinator.defer(() -> fail("Closed")));
			assertFalse(coordinator.hasRequests());
			ContentNavigationCoordinator pending = new ContentNavigationCoordinator(host);
			int ticket = pending.begin(new AboutFragment(), new PageItem());
			pending.setSaveSessionPending(true); assertTrue(pending.shouldSaveSession());
			pending.close(); assertFalse(pending.complete(ticket)); assertNull(pending.pendingPageItem());
		});
	}

	@Test public void deferredRequestsKeepTheirOwnPresentationAndDoNotLeakItToLaterRequests() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentHost host = new ContentHost();
			ContentNavigationCoordinator coordinator = new ContentNavigationCoordinator(host);
			host.stateSaved = true;
			ArrayList<Integer> observed = new ArrayList<>();
			coordinator.runWithTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE, () -> {
				assertTrue(coordinator.defer(() -> observed.add(coordinator.requestTransition())));
			});
			coordinator.runWithTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE, () -> {
				assertTrue(coordinator.defer(() -> observed.add(coordinator.requestTransition())));
			});
			assertTrue(coordinator.defer(() -> observed.add(coordinator.requestTransition())));
			assertEquals(FragmentTransaction.TRANSIT_NONE, coordinator.requestTransition());
			host.stateSaved = false;
			coordinator.scheduleDrain(); host.drain(); host.drain(); host.drain();
			assertEquals(Arrays.asList(FragmentTransaction.TRANSIT_FRAGMENT_FADE,
					FragmentTransaction.TRANSIT_FRAGMENT_CLOSE, FragmentTransaction.TRANSIT_NONE), observed);
			assertEquals(FragmentTransaction.TRANSIT_NONE, coordinator.requestTransition());
		});
	}

	@Test public void nestedPresentationScopesRestoreTheirCallerEvenAfterFailure() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentNavigationCoordinator coordinator = new ContentNavigationCoordinator(new ContentHost());
			coordinator.runWithTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE, () -> {
				assertEquals(FragmentTransaction.TRANSIT_FRAGMENT_FADE, coordinator.requestTransition());
				try {
					coordinator.runWithTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE,
							() -> { throw new IllegalStateException("fixture"); });
					fail("Expected fixture exception");
				} catch (IllegalStateException expected) {
					assertEquals("fixture", expected.getMessage());
				}
				assertEquals(FragmentTransaction.TRANSIT_FRAGMENT_FADE, coordinator.requestTransition());
			});
			assertEquals(FragmentTransaction.TRANSIT_NONE, coordinator.requestTransition());
		});
	}
	private static final class DrawerHost implements DrawerNavigationCoordinator.Host {
		boolean wide, visible = true;
		int closes, cancelled;
		Runnable afterClose;
		@Override public boolean isWide() { return wide; }
		@Override public boolean isDrawerVisible() { return visible; }
		@Override public void cancelPrewarm() { cancelled++; }
		@Override public void closeDrawer() { closes++; }
		@Override public void postAfterClose(Runnable work) { afterClose = work; }
	}
	@Test public void drawerStartsNavigationImmediatelyButDefersHeavyUiWork() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			DrawerHost host = new DrawerHost();
			DrawerNavigationCoordinator coordinator = new DrawerNavigationCoordinator(host);
			ArrayList<String> actions = new ArrayList<>();
			assertTrue(coordinator.schedule(() -> {
				actions.add("navigation");
				assertFalse(coordinator.schedule(() -> fail("Nested schedule")));
				assertTrue(coordinator.deferUiWork(() -> actions.add("ui")));
			}));
			assertEquals(Arrays.asList("navigation"), actions);
			assertEquals(1, host.closes); assertEquals(1, host.cancelled);
			coordinator.runPending(); assertEquals(1, actions.size());
			coordinator.finishTransition(); assertFalse(coordinator.isTransitionRunning());
			assertEquals(1, actions.size()); host.afterClose.run();
			assertEquals(Arrays.asList("navigation", "ui"), actions);
			host.wide = true; assertFalse(coordinator.schedule(() -> fail("Wide")));
			assertFalse(coordinator.deferUiWork(() -> fail("Idle")));
		});
	}
}
