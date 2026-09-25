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
			boolean incomplete, boolean changed, PagesDatabase.Cache.State revision, long epoch) {}
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
	public static Snapshot load(Chan chan, ContextGraph.Key target, List<Post> seed,
			boolean scan, CancellationSignal signal) {
		PagesDatabase db = PagesDatabase.getInstance();
		PagesDatabase.ThreadKey thread = new PagesDatabase.ThreadKey(target.source(), target.board(), target.thread());
		PagesDatabase.Cache.State revision = db.getCacheState(thread);
		long epoch = db.getContextEpoch();
		ContextGraph graph = new ContextGraph();
		Map<ContextGraph.Key, Post> posts = new TreeMap<>();
		int[] text = {0, 0}; boolean[] incomplete = {true};
		for (Post post : seed) add(chan, target, post, graph, posts, text, incomplete, signal);
		// Exact local lookups: selected post and missing predecessors, bounded independently of scans.
		Set<ContextGraph.Key> attempted = new HashSet<>();
		Set<ContextGraph.Key> frontier = new java.util.TreeSet<>(); frontier.add(target);
		int lookupBytes = 0;
		for (int round = 0; round < ContextGraph.MAX_DEPTH; round++) {
			Set<ContextGraph.Key> next = new java.util.TreeSet<>();
			for (ContextGraph.Key key : frontier) {
				signal.throwIfCanceled();
				if (!posts.containsKey(key) && attempted.size() < 200 && lookupBytes <= 16 * 1024 * 1024 - 262144 && attempted.add(key)) {
					PagesDatabase.ContextPage page = db.readContextPage(thread, null, number(key), signal);
					lookupBytes += page.bytes();
					for (Post post : page.posts()) add(chan, target, post, graph, posts, text, incomplete, signal);
				}
				next.addAll(graph.parents(key));
			}
			frontier = next;
			if (frontier.isEmpty()) break;
		}
		if (scan) {
			PostNumber after = null; int count = 0, bytes = 0;
			while (count <= ContextGraph.MAX_POSTS - 16 && bytes <= 12 * 1024 * 1024 && text[0] < ContextGraph.MAX_TEXT) {
				signal.throwIfCanceled();
				PagesDatabase.ContextPage page = db.readContextPage(thread, after, null, signal);
				if (page.scanned() == 0) break;
				count += page.scanned(); bytes += page.bytes(); after = page.last();
				for (Post post : page.posts()) if (!posts.containsKey(key(target.source(), target.board(), target.thread(), post.number))) {
					add(chan, target, post, graph, posts, text, incomplete, signal);
				}
			}
		}
		return new Snapshot(graph, Collections.unmodifiableMap(posts), incomplete[0],
				!revision.equals(db.getCacheState(thread)) || epoch != db.getContextEpoch(), revision, epoch);
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
