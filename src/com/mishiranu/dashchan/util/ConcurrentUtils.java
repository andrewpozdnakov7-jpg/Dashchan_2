package com.mishiranu.dashchan.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import androidx.annotation.NonNull;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentUtils {
	public static final Handler HANDLER = new Handler(Looper.getMainLooper());

	public static final Executor SEPARATE_EXECUTOR = command -> new Thread(command).start();
	public static final Executor PARALLEL_EXECUTOR = newThreadPool(1, 20, 3000, "ParallelExecutor", null);

	// 60 frames per second -> frame time is 1000 / 60 -> divide by 2
	public static final int HALF_FRAME_TIME_MS = 1000 / 60 / 2;

	public static ExecutorService newSingleThreadPool(int lifeTimeMs, String componentName, String componentPart) {
		return newThreadPool(lifeTimeMs > 0 ? 0 : 1, 1, lifeTimeMs, componentName, componentPart);
	}

	public static ExecutorService newIdleThreadPool(int threads, long idleTimeMs,
			String componentName, String componentPart) {
		return new IdleThreadPoolExecutor(threads, idleTimeMs,
				new ComponentThreadFactory(componentName, componentPart));
	}

	public static ExecutorService newThreadPool(int from, int to, long lifeTimeMs,
			String componentName, String componentPart) {
		if (to > from && to >= 2) {
			return new ScalingThreadPoolExecutor(from, to, lifeTimeMs,
					new ComponentThreadFactory(componentName, componentPart));
		} else {
			return new ThreadPoolExecutor(from, to, lifeTimeMs, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
					new ComponentThreadFactory(componentName, componentPart));
		}
	}

	private static class PriorityThread implements Runnable {
		private final Runnable runnable;
		private final int priority;

		private PriorityThread(Runnable runnable, int priority) {
			this.runnable = runnable;
			this.priority = priority;
		}

		@Override
		public void run() {
			Process.setThreadPriority(priority);
			runnable.run();
		}
	}

	private static class ComponentThreadFactory implements ThreadFactory {
		private final String name;
		private final String part;
		private final AtomicInteger number;

		public ComponentThreadFactory(String name, String part) {
			this.name = name;
			this.part = part;
			number = part != null ? null : new AtomicInteger();
		}

		@Override
		public Thread newThread(@NonNull Runnable r) {
			Thread thread = new Thread(new PriorityThread(r, Process.THREAD_PRIORITY_BACKGROUND));
			if (name != null) {
				thread.setName(name + " #" + (part != null ? part : number.incrementAndGet()));
			}
			return thread;
		}
	}

	public static boolean isMain() {
		return Looper.myLooper() == Looper.getMainLooper();
	}

	private static class MainGetResult<T> {
		private T value;
		private Throwable error;
	}

	public static <T> T mainGet(Callable<T> callable) {
		if (callable == null) {
			return null;
		}
		CountDownLatch latch = new CountDownLatch(1);
		MainGetResult<T> result = new MainGetResult<>();
		Runnable runnable = () -> {
			try {
				result.value = callable.call();
			} catch (Throwable t) {
				result.error = t;
			}
			latch.countDown();
		};
		if (isMain()) {
			runnable.run();
		} else {
			HANDLER.post(runnable);
			boolean interrupted = false;
			while (true) {
				try {
					latch.await();
					if (interrupted) {
						Thread.currentThread().interrupt();
					}
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		}
		if (result.error != null) {
			if (result.error instanceof RuntimeException) {
				throw (RuntimeException) result.error;
			}
			if (result.error instanceof Error) {
				throw (Error) result.error;
			}
			throw new RuntimeException(result.error);
		}
		return result.value;
	}
}
