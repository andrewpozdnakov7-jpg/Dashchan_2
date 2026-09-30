package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class IdleThreadPoolExecutorTest {
	private static IdleThreadPoolExecutor create() {
		return new IdleThreadPoolExecutor(3, 60000, Executors.defaultThreadFactory());
	}

	private static void blockThree(IdleThreadPoolExecutor executor, CountDownLatch started, CountDownLatch release) {
		for (int i = 0; i < 3; i++) {
			executor.submit(() -> {
				started.countDown();
				release.await();
				return null;
			});
		}
	}

	@Test public void threeStartTogetherAndFourthWaitsForCapacity() throws Exception {
		IdleThreadPoolExecutor executor = create();
		CountDownLatch started = new CountDownLatch(3);
		CountDownLatch release = new CountDownLatch(1);
		try {
			blockThree(executor, started, release);
			assertTrue(started.await(5, TimeUnit.SECONDS));
			Future<Integer> fourth = executor.submit(() -> 4);
			assertFalse(fourth.isDone());
			assertEquals(3, executor.getPoolSize());
			assertEquals(1, executor.getQueue().size());
			release.countDown();
			assertEquals(Integer.valueOf(4), fourth.get(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test public void twoOwnersCanRunSixTasksWithoutSerializingChans() throws Exception {
		IdleThreadPoolExecutor first = create();
		IdleThreadPoolExecutor second = create();
		CountDownLatch started = new CountDownLatch(6);
		CountDownLatch release = new CountDownLatch(1);
		try {
			blockThree(first, started, release);
			blockThree(second, started, release);
			assertTrue(started.await(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			first.shutdownNow();
			second.shutdownNow();
			assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test public void idleWorkersExitAndThreeWorkersCanRestart() throws Exception {
		List<Thread> threads = new CopyOnWriteArrayList<>();
		IdleThreadPoolExecutor executor = new IdleThreadPoolExecutor(3, 50, runnable -> {
			Thread thread = new Thread(runnable);
			threads.add(thread);
			return thread;
		});
		CountDownLatch firstStarted = new CountDownLatch(3);
		CountDownLatch firstRelease = new CountDownLatch(1);
		CountDownLatch nextRelease = new CountDownLatch(1);
		try {
			blockThree(executor, firstStarted, firstRelease);
			assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
			firstRelease.countDown();
			for (Thread thread : threads) {
				thread.join(5000);
				assertFalse(thread.isAlive());
			}
			assertEquals(0, executor.getPoolSize());
			CountDownLatch nextStarted = new CountDownLatch(3);
			blockThree(executor, nextStarted, nextRelease);
			assertTrue(nextStarted.await(5, TimeUnit.SECONDS));
			assertEquals(6, threads.size());
		} finally {
			firstRelease.countDown();
			nextRelease.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test public void shutdownRejectsNewWork() throws Exception {
		IdleThreadPoolExecutor executor = create();
		executor.shutdown();
		try {
			executor.submit(() -> {});
			fail("A closed executor must reject work");
		} catch (RejectedExecutionException expected) {
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}
}
