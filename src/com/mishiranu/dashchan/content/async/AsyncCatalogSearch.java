package com.mishiranu.dashchan.content.async;

import android.os.SystemClock;
import android.util.Log;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Main-thread owner. Mutable model extraction stays on main; pure string work uses one CPU worker. */
public final class AsyncCatalogSearch<T> {
	private static final ThreadPoolExecutor EXECUTOR = (ThreadPoolExecutor)
			ConcurrentUtils.newSingleThreadPool(3000, "CatalogSearch", null);
	private final IdentityHashMap<T, CatalogSearch.Document> documents = new IdentityHashMap<>();
	private final Function<T, CatalogSearch.Document> capture;
	private final Consumer<List<T>> onResult;
	private long generation;
	private Runnable pendingCapture;
	private SearchTask task;

	public AsyncCatalogSearch(Function<T, CatalogSearch.Document> capture, Consumer<List<T>> onResult) {
		this.capture = capture;
		this.onResult = onResult;
	}

	/** Identity is a content revision: PostItem wraps a final Post; refresh supplies replacement items. */
	public void retainItems(List<T> items) {
		IdentityHashMap<T, Boolean> retained = new IdentityHashMap<>();
		for (T item : items) retained.put(item, Boolean.TRUE);
		documents.keySet().retainAll(retained.keySet());
	}

	public void cancel() {
		generation++;
		if (pendingCapture != null) {
			ConcurrentUtils.HANDLER.removeCallbacks(pendingCapture);
			pendingCapture = null;
		}
		if (task != null) {
			task.request.cancel();
			task.cancel(true);
			EXECUTOR.remove(task);
			task = null;
		}
	}

	public void clear() {
		cancel();
		documents.clear();
	}

	public void submit(List<T> source, String query) {
		cancel();
		long requestGeneration = generation;
		Locale locale = Locale.getDefault();
		ArrayList<T> items = new ArrayList<>(source);
		ArrayList<CatalogSearch.Document> snapshot = new ArrayList<>(items.size());
		pendingCapture = new Runnable() {
			private int position;

			@Override
			public void run() {
				if (generation != requestGeneration) return;
				long start = SystemClock.elapsedRealtimeNanos();
				int processed = 0;
				while (position < items.size()) {
					T item = items.get(position++);
					CatalogSearch.Document document = documents.get(item);
					if (document == null) {
						document = capture.apply(item);
						documents.put(item, document);
						processed++;
					}
					snapshot.add(document);
					// Yield between posts. A forum's individual markup parser cannot be preempted safely.
					if (processed >= 32 || SystemClock.elapsedRealtimeNanos() - start >= 2_000_000L) break;
				}
				if (position < items.size()) {
					ConcurrentUtils.HANDLER.postDelayed(this, 8L);
				} else {
					pendingCapture = null;
					task = new SearchTask(new CatalogSearch.Request(snapshot, query, locale), items,
							query, locale, requestGeneration);
					EXECUTOR.execute(task);
				}
			}
		};
		ConcurrentUtils.HANDLER.postDelayed(pendingCapture, 100L);
	}

	private final class SearchTask extends FutureTask<int[]> {
		final CatalogSearch.Request request;
		final ArrayList<T> items;
		final String query;
		final Locale locale;
		final long requestGeneration;

		SearchTask(CatalogSearch.Request request, ArrayList<T> items, String query,
				Locale locale, long requestGeneration) {
			super(request::find);
			this.request = request;
			this.items = items;
			this.query = query;
			this.locale = locale;
			this.requestGeneration = requestGeneration;
		}

		@Override
		protected void done() {
			if (isCancelled()) return;
			try {
				int[] indices = get();
				if (indices == null) return;
				ConcurrentUtils.HANDLER.post(() -> {
					if (task != this || generation != requestGeneration || isCancelled()) return;
					task = null;
					if (!locale.equals(Locale.getDefault())) {
						submit(items, query);
						return;
					}
					ArrayList<T> result = new ArrayList<>(indices.length);
					for (int index : indices) result.add(items.get(index));
					onResult.accept(result);
				});
			} catch (CancellationException e) {
				// Replaced query or screen left.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				Log.e("CatalogSearch", "Background search failed", e.getCause());
				ConcurrentUtils.HANDLER.post(() -> {
					if (task == this) task = null;
				});
			}
		}
	}
}
