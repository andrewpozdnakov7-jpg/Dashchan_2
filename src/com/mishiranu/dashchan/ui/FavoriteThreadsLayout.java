package com.mishiranu.dashchan.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Presentation only: never sorts/mutates the persisted favorites list. */
final class FavoriteThreadsLayout {
	private FavoriteThreadsLayout() {}
	static final class Group<T> {
		final String forum, board;
		final List<T> items = new ArrayList<>();
		Group(String forum, String board) { this.forum = forum; this.board = board; }
	}
	private static final class Key {
		final String forum, board;
		Key(String forum, String board) { this.forum = forum; this.board = board; }
		@Override public boolean equals(Object other) {
			return other instanceof Key && Objects.equals(forum, ((Key) other).forum)
					&& Objects.equals(board, ((Key) other).board);
		}
		@Override public int hashCode() { return Objects.hash(forum, board); }
	}
	static <T> List<Group<T>> arrange(List<T> source, Function<T, String> forum,
			Function<T, String> board, Predicate<T> unread, boolean grouped, boolean unreadFirst) {
		Map<Key, Group<T>> groups = new LinkedHashMap<>();
		Map<String, Integer> forumOrder = new LinkedHashMap<>();
		for (T item : source) {
			String f = grouped ? forum.apply(item) : null, b = grouped ? board.apply(item) : null;
			if (!forumOrder.containsKey(f)) forumOrder.put(f, forumOrder.size());
			groups.computeIfAbsent(new Key(f, b), key -> new Group<>(key.forum, key.board)).items.add(item);
		}
		List<Group<T>> result = new ArrayList<>(groups.values());
		if (grouped) {
			// Keep the existing forum order, then alphabetize boards. Manual reordering
			// inside a board must not move its entire group above another board.
			Comparator<String> boards = Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER
					.thenComparing(Comparator.naturalOrder()));
			result.sort(Comparator.<Group<T>>comparingInt(group -> forumOrder.get(group.forum))
					.thenComparing(group -> group.board, boards));
		}
		if (unreadFirst) for (Group<T> group : result) {
			List<T> read = new ArrayList<>(), fresh = new ArrayList<>();
			for (T item : group.items) (unread.test(item) ? fresh : read).add(item);
			group.items.clear(); group.items.addAll(fresh); group.items.addAll(read);
		}
		return result;
	}
}
