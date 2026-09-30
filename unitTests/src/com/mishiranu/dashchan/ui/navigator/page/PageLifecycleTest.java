package com.mishiranu.dashchan.ui.navigator.page;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageLifecycleTest {
	private static final class Owner implements LifecycleOwner {
		final LifecycleRegistry registry = LifecycleRegistry.createUnsafe(this);
		final PageLifecycle page = new PageLifecycle(registry);
		final List<String> calls = new ArrayList<>();
		@Override public Lifecycle getLifecycle() { return registry; }
		void resume() { page.resume(() -> calls.add("resume")); }
		void pause() { page.pause(() -> calls.add("pause")); }
		void destroy() { page.destroy(() -> calls.add("pause"), () -> calls.add("destroy")); }
	}

	@Before public void synchronousLiveData() {
		ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
			@Override public void executeOnDiskIO(Runnable runnable) { runnable.run(); }
			@Override public void postToMainThread(Runnable runnable) { runnable.run(); }
			@Override public boolean isMainThread() { return true; }
		});
	}

	@After public void resetExecutor() { ArchTaskExecutor.getInstance().setDelegate(null); }

	@Test public void normalPauseResumeAndDestroyOrder() {
		Owner owner = new Owner();
		List<Lifecycle.Event> events = new ArrayList<>();
		owner.registry.addObserver((LifecycleEventObserver) (source, event) -> events.add(event));
		assertFalse(owner.page.isRunning());
		owner.page.start();
		assertEquals(Lifecycle.State.STARTED, owner.registry.getCurrentState());
		owner.resume(); owner.pause(); owner.resume(); owner.destroy();
		assertEquals(List.of("resume", "pause", "resume", "pause", "destroy"), owner.calls);
		assertEquals(List.of(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START,
				Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_RESUME,
				Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY), events);
		assertFalse(owner.page.isRunning());
		assertEquals(0, owner.registry.getObserverCount());
	}

	@Test public void duplicateCallbacksAndTerminalStateAreSafe() {
		Owner owner = new Owner();
		owner.page.start(); owner.page.start();
		owner.resume(); owner.resume(); owner.pause(); owner.pause();
		owner.destroy(); owner.destroy(); owner.resume(); owner.pause(); owner.page.start();
		assertEquals(List.of("resume", "pause", "destroy"), owner.calls);
		assertEquals(Lifecycle.State.DESTROYED, owner.registry.getCurrentState());
	}

	@Test public void pausedLivingViewStillReceivesResultsButDestroyedViewDoesNot() {
		Owner old = new Owner();
		MutableLiveData<Integer> result = new MutableLiveData<>();
		List<Integer> delivered = new ArrayList<>();
		result.observe(old, delivered::add);
		result.setValue(1);
		assertTrue(delivered.isEmpty());
		old.page.start(); old.resume(); old.pause();
		result.setValue(2);
		assertEquals(List.of(1, 2), delivered);
		old.destroy();
		result.setValue(3);
		assertEquals(List.of(1, 2), delivered);
		// The same retained task data can deliver to a new page/view after rotation.
		Owner restored = new Owner();
		result.observe(restored, delivered::add);
		restored.page.start();
		assertEquals(List.of(1, 2, 3), delivered);
		restored.destroy();
	}

	@Test public void navigationDuringResumeNotificationDoesNotResumeDeadPage() {
		Owner owner = new Owner();
		owner.registry.addObserver((LifecycleEventObserver) (source, event) -> {
			if (event == Lifecycle.Event.ON_RESUME) owner.destroy();
		});
		owner.page.start(); owner.resume();
		assertEquals(List.of("pause", "destroy"), owner.calls);
		assertFalse(owner.page.isRunning());
	}

	@Test public void cleanupCannotReenterOrResurrectPage() {
		Owner owner = new Owner();
		owner.page.start(); owner.resume();
		owner.page.destroy(() -> {
			owner.calls.add("pause");
			owner.resume(); owner.destroy();
		}, () -> { owner.calls.add("destroy"); owner.resume(); owner.destroy(); });
		assertEquals(List.of("resume", "pause", "destroy"), owner.calls);
		assertEquals(Lifecycle.State.DESTROYED, owner.registry.getCurrentState());
	}

	@Test public void processRecreationUsesNewOwnerNotOldRegistry() {
		Owner old = new Owner();
		old.page.start(); old.resume(); old.destroy();
		Owner fresh = new Owner();
		fresh.page.start(); fresh.resume();
		assertEquals(Lifecycle.State.DESTROYED, old.registry.getCurrentState());
		assertEquals(Lifecycle.State.RESUMED, fresh.registry.getCurrentState());
		assertEquals(List.of("resume"), fresh.calls);
		fresh.destroy();
	}
}
