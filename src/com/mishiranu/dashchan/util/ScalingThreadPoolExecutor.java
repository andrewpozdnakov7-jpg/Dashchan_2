package com.mishiranu.dashchan.util;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Grow before queueing, but leave shutdown checks and worker wakeup to ThreadPoolExecutor.
final class ScalingThreadPoolExecutor extends ThreadPoolExecutor {
	private static final class Queue extends LinkedBlockingQueue<Runnable> {
		private final ThreadLocal<Boolean> grow = ThreadLocal.withInitial(() -> false);

		@Override
		public boolean offer(Runnable runnable) {
			return !grow.get() && super.offer(runnable);
		}
	}

	private final Queue queue;

	ScalingThreadPoolExecutor(int from, int to, long lifeTimeMs, ThreadFactory threadFactory) {
		this(from, to, lifeTimeMs, threadFactory, new Queue());
	}

	private ScalingThreadPoolExecutor(int from, int to, long lifeTimeMs,
			ThreadFactory threadFactory, Queue queue) {
		super(from, to, lifeTimeMs, TimeUnit.MILLISECONDS, queue, threadFactory);
		this.queue = queue;
		super.setRejectedExecutionHandler((runnable, executor) -> {
			if (executor.isShutdown()) {
				throw new RejectedExecutionException("Executor is shut down");
			}
			queue.grow.set(false);
			// Do not put directly into the queue: shutdown can race with submission,
			// and the last worker can time out. execute() handles both cases.
			super.execute(runnable);
		});
	}

	@Override
	public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void execute(Runnable command) {
		try {
			queue.grow.set(true);
			super.execute(command);
		} finally {
			queue.grow.remove();
		}
	}
}
