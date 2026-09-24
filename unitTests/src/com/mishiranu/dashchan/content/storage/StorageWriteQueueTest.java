package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class StorageWriteQueueTest {
	@Test(timeout = 5000)
	public void newerSnapshotCannotOvertakeQueuedOlderSnapshot() throws Exception {
		ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		List<String> writes = new ArrayList<>();
		StorageWriteQueue queue = new StorageWriteQueue(tasks::add, e -> {});
		StorageWriteQueue.Ticket otherStorage = queue.enqueue(() -> writes.add("other"));
		StorageWriteQueue.Ticket old = queue.enqueue(() -> writes.add("OLD"));
		StorageWriteQueue.Ticket latest = queue.enqueue(() -> writes.add("NEW"));
		ExecutorService waiter = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> result = waiter.submit(latest::await);
			tasks.remove().run();
			tasks.remove().run();
			assertTrue(otherStorage.await());
			assertTrue(old.await());
			assertFalse(result.isDone());
			tasks.remove().run();
			assertTrue(result.get(1, TimeUnit.SECONDS));
			assertTrue(old.sequence < latest.sequence);
			assertEquals(Arrays.asList("other", "OLD", "NEW"), writes);
		} finally {
			waiter.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void awaitWaitsForAnAlreadyRunningWrite() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ExecutorService waiter = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			StorageWriteQueue queue = new StorageWriteQueue(executor, e -> {});
			StorageWriteQueue.Ticket ticket = queue.enqueue(() -> {
				entered.countDown();
				try {
					if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Test write timeout");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			});
			assertTrue(entered.await(1, TimeUnit.SECONDS));
			Future<Boolean> result = waiter.submit(ticket::await);
			try {
				result.get(30, TimeUnit.MILLISECONDS);
				throw new AssertionError("Await returned before the write completed");
			} catch (TimeoutException expected) {
				// No pending Android message is needed to wait for an in-flight ticket.
			}
			release.countDown();
			assertTrue(result.get(1, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			waiter.shutdownNow();
			executor.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void failureIsReportedAndDoesNotKillFollowingWrites() {
		ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		List<Exception> failures = new ArrayList<>();
		StorageWriteQueue queue = new StorageWriteQueue(tasks::add, failures::add);
		StorageWriteQueue.Ticket failed = queue.enqueue(() -> { throw new IOException("Test disk full"); });
		StorageWriteQueue.Ticket failedRuntime = queue.enqueue(() -> { throw new IllegalStateException("Test"); });
		StorageWriteQueue.Ticket retry = queue.enqueue(() -> {});
		while (!tasks.isEmpty()) tasks.remove().run();
		assertFalse(failed.await());
		assertTrue(failed.isFailed());
		assertFalse(failedRuntime.await());
		assertTrue(retry.await());
		assertFalse(retry.isFailed());
		assertEquals(2, failures.size());
	}

	@Test(timeout = 5000)
	public void interruptedWaitDoesNotAcknowledgeOrCancelTheWrite() throws Exception {
		ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		StorageWriteQueue queue = new StorageWriteQueue(tasks::add, e -> {});
		StorageWriteQueue.Ticket ticket = queue.enqueue(() -> {});
		AtomicBoolean successful = new AtomicBoolean(true);
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread waiter = new Thread(() -> {
			Thread.currentThread().interrupt();
			successful.set(ticket.await());
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		waiter.start();
		waiter.join(1000);
		assertFalse(waiter.isAlive());
		assertFalse(successful.get());
		assertTrue(interrupted.get());
		tasks.remove().run();
		assertTrue(ticket.await());
	}

	@Test(timeout = 5000)
	public void rejectedWriteCompletesWithFailure() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.shutdown();
		StorageWriteQueue queue = new StorageWriteQueue(executor, e -> {});
		StorageWriteQueue.Ticket ticket = queue.enqueue(() -> {});
		assertFalse(ticket.await());
		assertTrue(ticket.isFailed());
	}
}
