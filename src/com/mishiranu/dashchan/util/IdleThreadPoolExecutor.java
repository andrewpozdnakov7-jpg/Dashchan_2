package com.mishiranu.dashchan.util;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Fixed active concurrency with reclaimable idle workers. Each owner keeps its own pool. */
final class IdleThreadPoolExecutor extends ThreadPoolExecutor {
	IdleThreadPoolExecutor(int threads, long idleTimeMs, ThreadFactory threadFactory) {
		// Keep core == max: core=0 with an unbounded queue would normally queue work
		// behind a single worker rather than preserve the requested concurrency.
		super(threads, threads, idleTimeMs, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
		allowCoreThreadTimeOut(true);
	}
}
