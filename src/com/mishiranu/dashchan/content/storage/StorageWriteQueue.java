package com.mishiranu.dashchan.content.storage;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One FIFO writer shared by all storages. A ticket completes after the actual file commit. */
final class StorageWriteQueue {
	interface Action {
		void write() throws IOException;
	}

	static final class Ticket {
		final long sequence;
		private final CountDownLatch completed = new CountDownLatch(1);
		private volatile boolean successful;

		private Ticket(long sequence) {
			this.sequence = sequence;
		}

		boolean isFailed() {
			return completed.getCount() == 0 && !successful;
		}

		boolean await() {
			try {
				completed.await();
				return successful;
			} catch (InterruptedException e) {
				// Do not cancel a write another caller may also be waiting for.
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	private final Executor executor;
	private final Consumer<Exception> onFailure;
	private long nextSequence;

	StorageWriteQueue(Consumer<Exception> onFailure) {
		this(Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "StorageManagerWorker")),
				onFailure);
	}

	// The supplied executor must be serial/FIFO; used by deterministic unit tests.
	StorageWriteQueue(Executor executor, Consumer<Exception> onFailure) {
		this.executor = executor;
		this.onFailure = onFailure;
	}

	synchronized Ticket enqueue(Action action) {
		Ticket ticket = new Ticket(++nextSequence);
		try {
			executor.execute(() -> {
				try {
					action.write();
					ticket.successful = true;
				} catch (IOException | RuntimeException e) {
					onFailure.accept(e);
				} finally {
					ticket.completed.countDown();
				}
			});
		} catch (RuntimeException e) {
			try {
				onFailure.accept(e);
			} finally {
				ticket.completed.countDown();
			}
		}
		return ticket;
	}
}
