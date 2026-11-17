package com.mishiranu.dashchan.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure selection policy. Closing pages and scheduling remain with their navigation owner. */
public final class PageCleanupPolicy {
	private PageCleanupPolicy() {}
	public static final long AGE_CHECK_INTERVAL_MS = 15L * 60 * 1000;

	public static final class AgeGate {
		private long lastCheck = -1;
		public boolean isDue(long elapsed) {
			return lastCheck < 0 || elapsed < lastCheck || elapsed - lastCheck >= AGE_CHECK_INTERVAL_MS;
		}
		public void checked(long elapsed) { lastCheck = elapsed; }
		public void reset() { lastCheck = -1; }
	}

	public static final class Key {
		public final String chan, board, thread;
		public Key(String chan, String board, String thread) {
			this.chan = chan; this.board = board; this.thread = thread;
		}
		@Override public boolean equals(Object object) {
			if (!(object instanceof Key)) return false;
			Key other = (Key) object;
			return Objects.equals(chan, other.chan) && Objects.equals(board, other.board)
					&& Objects.equals(thread, other.thread);
		}
		@Override public int hashCode() { return Objects.hash(chan, board, thread); }
	}

	public static final class Entry {
		public final Key key;
		public final long visited, order;
		public final boolean protectedPage, deleted;
		public Entry(Key key, long visited, long order, boolean protectedPage, boolean deleted) {
			this.key = key; this.visited = visited; this.order = order;
			this.protectedPage = protectedPage; this.deleted = deleted;
		}
	}

	/** Protected pages are additional to the limit. The caller supplies one entry per logical thread. */
	public static Set<Key> select(List<Entry> entries, int maximum, long maximumAge,
			long now, boolean checkAge) {
		HashSet<Key> remove = new HashSet<>();
		ArrayList<Entry> remaining = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.protectedPage) continue;
			boolean expired = checkAge && maximumAge > 0 && entry.visited > 0 && now >= entry.visited
					&& now - entry.visited >= maximumAge;
			if (entry.deleted || expired) remove.add(entry.key);
			else remaining.add(entry);
		}
		if (maximum > 0 && remaining.size() > maximum) {
			remaining.sort(Comparator.comparingLong((Entry entry) -> entry.order)
					.thenComparingLong(entry -> entry.visited));
			for (int i = 0; i < remaining.size() - maximum; i++) remove.add(remaining.get(i).key);
		}
		return remove;
	}
}
