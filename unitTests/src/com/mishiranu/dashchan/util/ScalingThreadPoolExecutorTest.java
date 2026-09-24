package com.mishiranu.dashchan.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ScalingThreadPoolExecutorTest {
	@Test
	public void shutdownRejectsSubmissionInsteadOfReturningStrandedFuture() throws Exception {
		ScalingThreadPoolExecutor executor = new ScalingThreadPoolExecutor(0, 2, 100,
				Executors.defaultThreadFactory());
		executor.shutdown();
		assertRejected(executor);
		assertTrue(executor.getQueue().isEmpty());
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	@Test
	public void shutdownNowRejectsSubmission() throws Exception {
		ScalingThreadPoolExecutor executor = new ScalingThreadPoolExecutor(1, 2, 100,
				Executors.defaultThreadFactory());
		executor.shutdownNow();
		assertRejected(executor);
		assertTrue(executor.getQueue().isEmpty());
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	@Test
	public void saturationQueuesWorkAndGracefulShutdownDrainsIt() throws Exception {
		ScalingThreadPoolExecutor executor = new ScalingThreadPoolExecutor(0, 2, 100,
				Executors.defaultThreadFactory());
		CountDownLatch started = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		try {
			for (int i = 0; i < 2; i++) {
				executor.submit(() -> {
					started.countDown();
					release.await();
					return null;
				});
			}
			assertTrue(started.await(5, TimeUnit.SECONDS));
			Future<Integer> queued = executor.submit(() -> 42);
			assertEquals(1, executor.getQueue().size());
			executor.shutdown();
			assertRejected(executor);
			release.countDown();
			assertEquals(Integer.valueOf(42), queued.get(5, TimeUnit.SECONDS));
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void shutdownDuringWorkerCreationRejectsRatherThanRequeues() throws Exception {
		AtomicReference<ScalingThreadPoolExecutor> reference = new AtomicReference<>();
		ScalingThreadPoolExecutor executor = new ScalingThreadPoolExecutor(0, 2, 100, runnable -> {
			reference.get().shutdown();
			return new Thread(runnable);
		});
		reference.set(executor);
		assertRejected(executor);
		assertTrue(executor.getQueue().isEmpty());
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
	}

	private static void assertRejected(ScalingThreadPoolExecutor executor) {
		try {
			executor.submit(() -> {});
			fail("Submission after shutdown must throw");
		} catch (RejectedExecutionException expected) {
			// No Future that can wait forever is returned to the caller.
		}
	}
}
