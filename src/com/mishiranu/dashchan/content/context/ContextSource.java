package com.mishiranu.dashchan.content.context;

import android.net.Uri;
import android.os.CancellationSignal;
import chan.content.Chan;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Worker-owned snapshot, strictly local. Does not create PostItems or touch Android views. */
public final class ContextSource {
	public record Snapshot(ContextGraph graph, Map<ContextGraph.Key, Post> posts,
			boolean incomplete, boolean changed, PagesDatabase.Cache.State revision, long epoch, ContextGraph.Key target) {}
	/** Revision belongs to the applied extraction result, never sampled from DB beside arbitrary UI objects. */
	public record Seed(PagesDatabase.Cache.State revision, long epoch, List<Post> posts) {
		public Seed { posts = List.copyOf(posts); }
	}
	public static boolean reusable(Snapshot snapshot, ContextGraph.Key target,
			PagesDatabase.Cache.State revision, long epoch) {
		return snapshot != null && !snapshot.changed() && snapshot.target().sameThread(target)
				&& ContextSnapshotGuard.isCurrent(snapshot.revision(), snapshot.epoch(), revision, epoch)
				&& snapshot.posts().containsKey(target) && (!snapshot.incomplete() || snapshot.target().equals(target));
	}
	public static ContextGraph.Key key(String chan, String board, String thread, PostNumber number) {
		return new ContextGraph.Key(chan, board, thread, number.major, number.minor);
	}
	public static PostNumber number(ContextGraph.Key key) { return new PostNumber(key.major(), key.minor()); }
	public static ContextGraph.Key resolve(Chan chan, ContextGraph.Key origin, String href) {
		try {
			Uri base = chan.locator.safe(false).createThreadUri(origin.board(), origin.thread());
			if (base == null) return null;
			Uri uri = chan.locator.convert(Uri.parse(new URI(base.toString()).resolve(href).toString()));
			if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
					|| !chan.locator.isChanHost(uri.getHost()) || !chan.locator.safe(false).isThreadUri(uri)) return null;
			String board = chan.locator.safe(false).getBoardName(uri);
			String thread = chan.locator.safe(false).getThreadNumber(uri);
			PostNumber post = chan.locator.safe(false).getPostNumber(uri);
			if (post == null || thread == null) return null;
			ContextGraph.Key result = key(chan.name, board, thread, post);
			return origin.sameThread(result) ? result : null;
		} catch (java.net.URISyntaxException | IllegalArgumentException e) { return null; }
	}
	public static Snapshot load(Chan chan, ContextGraph.Key target, Seed seed, CancellationSignal signal) {
		long started = android.os.SystemClock.elapsedRealtime();
		PagesDatabase db = PagesDatabase.getInstance();
		PagesDatabase.ThreadKey thread = new PagesDatabase.ThreadKey(target.source(), target.board(), target.thread());
		PagesDatabase.Cache.State revision = db.getCacheState(thread);
		long epoch = db.getContextEpoch();
		ContextGraph graph = new ContextGraph();
		Map<ContextGraph.Key, Post> posts = new TreeMap<>();
		int[] text = {0, 0}; boolean[] incomplete = {false};
		int queries = 0, bytes = 0;
		int[] lookup = {0, 0};
		boolean useSeed = seed != null && ContextSnapshotGuard.isCurrent(seed.revision(), seed.epoch(), revision, epoch);
		if (useSeed) {
			Map<PostNumber, Post> indexed = new TreeMap<>();
			for (Post post : seed.posts()) { signal.throwIfCanceled(); indexed.put(post.number, post); }
			// Selected card gets first claim on the budgets, even near the end of a long thread.
			addPredecessors(chan, target, indexed, thread, graph, posts, text, incomplete, lookup, signal);
			for (Post post : indexed.values()) add(chan, target, post, graph, posts, text, incomplete, signal);
		} else {
			addPredecessors(chan, target, null, thread, graph, posts, text, incomplete, lookup, signal);
			Set<PostNumber> loaded = new HashSet<>();
			for (ContextGraph.Key key : posts.keySet()) loaded.add(number(key));
			PostNumber after = null; int count = 0; boolean exhausted = false;
			while (count < ContextGraph.MAX_POSTS && bytes < 12 * 1024 * 1024 && text[0] < ContextGraph.MAX_TEXT) {
				signal.throwIfCanceled();
				PagesDatabase.ContextPage page = db.readContextPage(thread, after, null, loaded, signal);
				queries++; count += page.scanned(); bytes += page.bytes(); incomplete[0] |= page.skipped();
				if (page.scanned() == 0) { exhausted = true; break; }
				after = page.last();
				for (Post post : page.posts()) add(chan, target, post, graph, posts, text, incomplete, signal);
			}
			incomplete[0] |= !exhausted;
		}
		signal.throwIfCanceled();
		boolean changed = !ContextSnapshotGuard.isCurrent(revision, epoch, db.getCacheState(thread), db.getContextEpoch());
		android.util.Log.d("DiscussionContext", "snapshot source=" + (useSeed ? "loaded_posts" : "local_db")
				+ " posts=" + posts.size() + " queries=" + (queries + lookup[0]) + " bytes=" + (bytes + lookup[1])
				+ " incomplete=" + incomplete[0] + " changed=" + changed
				+ " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
		return new Snapshot(graph, Collections.unmodifiableMap(posts), incomplete[0], changed, revision, epoch, target);
	}

	private static void addPredecessors(Chan chan, ContextGraph.Key target, Map<PostNumber, Post> seed,
			PagesDatabase.ThreadKey thread, ContextGraph graph, Map<ContextGraph.Key, Post> posts,
			int[] text, boolean[] incomplete, int[] lookup, CancellationSignal signal) {
		// Preserve the selected post and ancestors before spending the scan budget on unrelated posts.
		Set<ContextGraph.Key> attempted = new HashSet<>();
		Set<ContextGraph.Key> frontier = new java.util.TreeSet<>(); frontier.add(target);
		for (int round = 0; round < ContextGraph.MAX_DEPTH; round++) {
			Set<ContextGraph.Key> next = new java.util.TreeSet<>();
			for (ContextGraph.Key key : frontier) {
				signal.throwIfCanceled();
				if (!posts.containsKey(key) && attempted.size() < 200 && lookup[1] <= 16 * 1024 * 1024 - 262144 && attempted.add(key)) {
					if (seed != null) {
						Post post = seed.get(number(key));
						if (post != null) add(chan, target, post, graph, posts, text, incomplete, signal);
					} else {
						PagesDatabase.ContextPage page = PagesDatabase.getInstance().readContextPage(thread, null, number(key), signal);
						lookup[0]++; lookup[1] += page.bytes(); incomplete[0] |= page.skipped();
						for (Post post : page.posts()) add(chan, target, post, graph, posts, text, incomplete, signal);
					}
				}
				next.addAll(graph.parents(key));
			}
			frontier = next;
			if (frontier.isEmpty()) break;
		}
	}
	private static boolean add(Chan chan, ContextGraph.Key scope, Post post, ContextGraph graph,
			Map<ContextGraph.Key, Post> posts, int[] text, boolean[] incomplete, CancellationSignal signal) {
		signal.throwIfCanceled();
		ContextGraph.Key key = key(scope.source(), scope.board(), scope.thread(), post.number);
		if (posts.containsKey(key)) return false;
		if (posts.size() >= ContextGraph.MAX_POSTS || post.comment.length() > ContextGraph.MAX_POST_TEXT
				|| text[0] + post.comment.length() > ContextGraph.MAX_TEXT || text[1] >= ContextGraph.MAX_EDGES) {
			incomplete[0] = true; return false;
		}
		text[0] += post.comment.length();
		ContextLinks.Links parsed = ContextLinks.read(post.comment, signal::isCanceled);
		incomplete[0] |= parsed.incomplete();
		List<ContextGraph.Key> links = new ArrayList<>();
		for (String href : parsed.hrefs()) {
			signal.throwIfCanceled();
			if (text[1]++ >= ContextGraph.MAX_EDGES) { incomplete[0] = true; break; }
			ContextGraph.Key parent = resolve(chan, key, href);
			if (parent != null) links.add(parent);
		}
		graph.add(key, links); posts.put(key, post); return true;
	}
}
