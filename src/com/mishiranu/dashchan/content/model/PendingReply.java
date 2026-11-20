package com.mishiranu.dashchan.content.model;

import chan.content.ChanPerformer;
import chan.util.StringUtils;
import com.mishiranu.dashchan.text.HtmlParser;
import com.mishiranu.dashchan.util.Hasher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One acknowledged reply for which the site did not supply a post number. */
public record PendingReply(long id, PostNumber after, long started, long accepted, String signature) {
	private static final long CLOCK_TOLERANCE = 30_000L;

	public static PostNumber cacheAfter(List<PendingReply> pending, PostNumber cached) {
		for (PendingReply reply : pending) {
			if (cached == null || reply.after == null) return null;
			if (reply.after.compareTo(cached) < 0) cached = reply.after;
		}
		return cached;
	}

	private boolean admits(Post post) {
		return !post.deleted && post.timestamp > 0 && post.timestamp >= started - CLOCK_TOLERANCE
				&& post.timestamp <= accepted + CLOCK_TOLERANCE
				&& (after == null || post.number.compareTo(after) > 0);
	}

	private static String digest(String text) {
		return StringUtils.formatHex(Hasher.getInstanceSha256().calculate(text));
	}

	private static String fileToken(String name, long size) {
		return name == null || name.isEmpty() || size <= 0 ? null : digest(name) + "/" + size;
	}

	private static String signature(String comment, ArrayList<String> files) {
		// Keep case, punctuation, emoji, word order and quote targets.
		String body = comment != null ? comment.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim() : "";
		if (body.isEmpty() && files.isEmpty()) return null;
		Collections.sort(files);
		StringBuilder key = new StringBuilder(digest(body));
		for (String file : files) key.append('|').append(file);
		return digest(key.toString());
	}

	public static String outgoing(String comment, ChanPerformer.SendPostData.Attachment[] attachments) {
		ArrayList<String> files = new ArrayList<>();
		if (attachments != null) {
			for (ChanPerformer.SendPostData.Attachment file : attachments) {
				String token = fileToken(file.getFileName(), file.getSize());
				if (token == null) return null;
				files.add(token);
			}
		}
		return signature(comment, files);
	}

	private static String incoming(Post post) {
		ArrayList<String> files = new ArrayList<>();
		for (Post.Attachment attachment : post.attachments) {
			if (attachment instanceof Post.Attachment.File file) {
				String token = fileToken(file.originalName, file.size);
				if (token == null) return null;
				files.add(token);
			}
		}
		return signature(HtmlParser.clear(post.comment), files);
	}

	/** Both inputs are sorted: receipts by send order, posts by distinct ascending number. */
	public static Map<PendingReply, Post> match(List<PendingReply> pending, List<Post> posts,
			PostNumber op, Set<PostNumber> owned) {
		Map<String, List<PendingReply>> groups = new HashMap<>();
		for (PendingReply reply : pending) {
			groups.computeIfAbsent(reply.signature, key -> new ArrayList<>()).add(reply);
		}
		Map<String, List<Post>> candidates = new HashMap<>();
		for (Post post : posts) {
			if (post.number.equals(op) || post.deleted || owned.contains(post.number)) continue;
			boolean inWindow = false;
			for (PendingReply reply : pending) {
				if (reply.admits(post)) {
					inWindow = true;
					break;
				}
			}
			if (!inWindow) continue;
			String key = incoming(post);
			List<PendingReply> group = groups.get(key);
			if (group == null) continue;
			for (PendingReply reply : group) {
				if (reply.admits(post)) {
					candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(post);
					break;
				}
			}
		}
		Map<PendingReply, Post> matches = new LinkedHashMap<>();
		for (Map.Entry<String, List<Post>> entry : candidates.entrySet()) {
			List<Post> found = entry.getValue();
			List<PendingReply> group = new ArrayList<>();
			for (PendingReply reply : groups.get(entry.getKey())) {
				for (Post post : found) {
					if (reply.admits(post)) {
						group.add(reply);
						break;
					}
				}
			}
			if (group.size() != found.size()) continue;
			boolean valid = true;
			for (int i = 0; i < group.size(); i++) {
				if (!group.get(i).admits(found.get(i))) {
					valid = false;
					break;
				}
			}
			if (valid) {
				for (int i = 0; i < group.size(); i++) matches.put(group.get(i), found.get(i));
			}
		}
		return matches;
	}
}
