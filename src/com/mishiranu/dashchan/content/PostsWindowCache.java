package com.mishiranu.dashchan.content;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, view-free windows used by the experimental thread reader. */
public final class PostsWindowCache {
	public static final int WINDOW_SIZE = 50;
	private static final int MAX_WINDOWS = 100;
	private static final long ABSOLUTE_MAX_BYTES = 128L * 1024L * 1024L;
	private static final long MIN_BYTES = 32L * 1024L * 1024L;

	public static final class Window {
		public final PagesDatabase.ThreadKey threadKey;
		public final PagesDatabase.Cache.State cacheState;
		public final int totalCount;
		public final int startPosition;
		public final List<PostNumber> postNumbers;
		public final Map<PostNumber, PostItem> postItems;
		private final long estimatedMemoryBytes;

		public Window(PagesDatabase.ThreadKey threadKey, PagesDatabase.Cache.State cacheState,
				int totalCount, int startPosition, List<PostNumber> postNumbers,
				Map<PostNumber, PostItem> postItems, long estimatedMemoryBytes) {
			this.threadKey = threadKey;
			this.cacheState = cacheState;
			this.totalCount = totalCount;
			this.startPosition = startPosition;
			this.postNumbers = Collections.unmodifiableList(new ArrayList<>(postNumbers));
			this.postItems = Collections.unmodifiableMap(new HashMap<>(postItems));
			this.estimatedMemoryBytes = Math.max(4096L, estimatedMemoryBytes +
					postNumbers.size() * 64L + postItems.size() * 96L);
		}

		public int getEndPosition() {
			return startPosition + postNumbers.size();
		}

		public boolean containsPosition(int position) {
			return position >= startPosition && position < getEndPosition();
		}

		public PostItem getItem(int position) {
			if (!containsPosition(position)) {
				return null;
			}
			return postItems.get(postNumbers.get(position - startPosition));
		}
	}

	private static final PostsWindowCache INSTANCE = new PostsWindowCache();

	public static PostsWindowCache getInstance() {
		return INSTANCE;
	}

	private final LinkedHashMap<PagesDatabase.ThreadKey, Window> windows =
			new LinkedHashMap<>(16, 0.75f, true);
	private final long maxBytes;
	private long currentBytes;

	private PostsWindowCache() {
		ActivityManager activityManager = (ActivityManager) MainApplication.getInstance()
				.getSystemService(Context.ACTIVITY_SERVICE);
		int memoryClass = activityManager != null ? activityManager.getMemoryClass() : 256;
		long adaptiveBytes = memoryClass * 1024L * 1024L / 4L;
		maxBytes = Math.min(ABSOLUTE_MAX_BYTES, Math.max(MIN_BYTES, adaptiveBytes));
	}

	public synchronized Window get(PagesDatabase.ThreadKey threadKey) {
		Window window = windows.get(threadKey);
		if (window != null && !window.cacheState.equals(PagesDatabase.getInstance().getCacheState(threadKey))) {
			removeLocked(threadKey);
			window = null;
		}
		return window;
	}

	public synchronized void put(Window window) {
		removeLocked(window.threadKey);
		windows.put(window.threadKey, window);
		currentBytes += window.estimatedMemoryBytes;
		trimLocked(maxBytes, MAX_WINDOWS);
	}

	public synchronized void invalidate(PagesDatabase.ThreadKey threadKey) {
		removeLocked(threadKey);
	}

	public synchronized void onTrimMemory(int level) {
		if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
			windows.clear();
			currentBytes = 0L;
		} else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
			trimLocked(maxBytes / 4L, MAX_WINDOWS / 4);
		} else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			trimLocked(maxBytes / 2L, MAX_WINDOWS / 2);
		}
	}

	private void removeLocked(PagesDatabase.ThreadKey threadKey) {
		Window removed = windows.remove(threadKey);
		if (removed != null) {
			currentBytes -= removed.estimatedMemoryBytes;
		}
	}

	private void trimLocked(long byteLimit, int windowLimit) {
		while ((currentBytes > byteLimit || windows.size() > windowLimit) && !windows.isEmpty()) {
			Map.Entry<PagesDatabase.ThreadKey, Window> oldest = windows.entrySet().iterator().next();
			currentBytes -= oldest.getValue().estimatedMemoryBytes;
			windows.remove(oldest.getKey());
		}
	}
}
