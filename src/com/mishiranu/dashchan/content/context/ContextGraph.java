package com.mishiranu.dashchan.content.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Pure, thread-scoped graph. No Android, I/O, rendering or read-state writes. */
public final class ContextGraph {
	public static final int INITIAL = 20, PAGE = 20, MAX_CARDS = 200, MAX_DEPTH = 12;
	public static final int MAX_POSTS = 5000, MAX_EDGES = 20000, MAX_TEXT = 4_000_000;
	public static final int MAX_POST_TEXT = 128_000;
	public record Key(String source, String board, String thread, int major, int minor) implements Comparable<Key> {
		public Key { board = board == null ? "" : board; }
		public boolean sameThread(Key other) {
			return source.equals(other.source) && board.equals(other.board) && thread.equals(other.thread);
		}
		public String number() { return minor == 0 ? Integer.toString(major) : major + "." + minor; }
		@Override public int compareTo(Key other) {
			int c = source.compareTo(other.source);
			if (c == 0) c = board.compareTo(other.board);
			if (c == 0) c = thread.compareTo(other.thread);
			if (c == 0) c = Integer.compare(major, other.major);
			return c != 0 ? c : Integer.compare(minor, other.minor);
		}
	}
	public enum Role { BEFORE, SELECTED, REPLY }
	public record Entry(Key key, Role role) {}
	public record Result(List<Entry> entries, boolean limited) {}
	private record Step(Key key, int depth) {}
	private final Map<Key, Set<Key>> parents = new TreeMap<>(), children = new TreeMap<>();
	private int edges;
	private boolean truncated;

	public void add(Key key, Collection<Key> links) {
		if (!parents.containsKey(key) && parents.size() >= MAX_POSTS) { truncated = true; return; }
		Set<Key> old = parents.get(key);
		if (old != null) for (Key parent : old) { children.get(parent).remove(key); edges--; }
		Set<Key> normalized = new TreeSet<>();
		for (Key parent : links) {
			if (edges >= MAX_EDGES) { truncated = true; break; }
			if (key.sameThread(parent) && !key.equals(parent) && normalized.add(parent)) {
				children.computeIfAbsent(parent, k -> new TreeSet<>()).add(key);
				edges++;
			}
		}
		parents.put(key, normalized);
	}
	public Set<Key> parents(Key key) { return Collections.unmodifiableSet(parents.getOrDefault(key, Collections.emptySet())); }
	public Result build(Key target, int beforeDepth, Set<Key> expanded, int limit,
			Collection<Key> retained, BooleanSupplier cancelled) {
		limit = Math.max(1, Math.min(MAX_CARDS, limit));
		Map<Key, Integer> before = walk(target, parents, Math.min(MAX_DEPTH, beforeDepth), null, cancelled);
		Map<Key, Integer> after = walk(target, children, MAX_DEPTH, expanded, cancelled);
		Set<Key> chosen = new HashSet<>();
		chosen.add(target);
		for (Key key : retained) if (chosen.size() < limit && target.sameThread(key)
				&& (before.containsKey(key) || after.containsKey(key))) chosen.add(key);
		// Alternate equally distant predecessors and replies; neither side monopolizes the first page.
		for (int depth = 1; depth <= MAX_DEPTH && chosen.size() < limit; depth++) {
			List<Key> left = atDepth(before, depth), right = atDepth(after, depth);
			for (int i = 0; i < Math.max(left.size(), right.size()) && chosen.size() < limit; i++) {
				if (i < left.size()) chosen.add(left.get(i));
				if (i < right.size() && chosen.size() < limit) chosen.add(right.get(i));
			}
		}
		List<Entry> entries = new ArrayList<>();
		for (Key key : new TreeSet<>(chosen)) if (!key.equals(target) && before.containsKey(key)) entries.add(new Entry(key, Role.BEFORE));
		entries.add(new Entry(target, Role.SELECTED));
		for (Key key : new TreeSet<>(chosen)) if (!key.equals(target) && !before.containsKey(key)) entries.add(new Entry(key, Role.REPLY));
		Set<Key> all = new HashSet<>(before.keySet()); all.addAll(after.keySet()); all.add(target);
		return new Result(Collections.unmodifiableList(entries), truncated || all.size() > chosen.size());
	}
	private static List<Key> atDepth(Map<Key, Integer> depths, int depth) {
		List<Key> result = new ArrayList<>();
		for (Key key : new TreeSet<>(depths.keySet())) if (depths.get(key) == depth) result.add(key);
		return result;
	}
	private static Map<Key, Integer> walk(Key target, Map<Key, Set<Key>> links, int maxDepth,
			Set<Key> expanded, BooleanSupplier cancelled) {
		Map<Key, Integer> depths = new HashMap<>();
		Set<Key> visited = new HashSet<>(); visited.add(target);
		ArrayDeque<Step> queue = new ArrayDeque<>(); queue.add(new Step(target, 0));
		while (!queue.isEmpty()) {
			if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
			Step step = queue.remove();
			if (step.depth >= maxDepth || expanded != null && step.depth > 0 && !expanded.contains(step.key)) continue;
			for (Key key : links.getOrDefault(step.key, Collections.emptySet())) if (visited.add(key)) {
				depths.put(key, step.depth + 1); queue.add(new Step(key, step.depth + 1));
			}
		}
		return depths;
	}
}
