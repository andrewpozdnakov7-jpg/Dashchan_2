package com.mishiranu.dashchan.chan.kohlchan;

import chan.content.model.FileAttachment;
import chan.content.model.Icon;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class KohlchanModelMapper {
	private KohlchanModelMapper() {}

	private static boolean flag(JSONObject object, String key) {
		return object.optBoolean(key, false) || object.optInt(key, 0) != 0;
	}

	private static String text(JSONObject object, String key) {
		String value = CommonUtils.optJsonString(object, key);
		return value != null ? StringUtils.nullIfEmpty(StringUtils.clearHtml(value).trim()) : null;
	}

	private static Post createPost(JSONObject object, KohlchanChanLocator locator, String parent)
			throws JSONException {
		Post post = new Post();
		post.setPostNumber(CommonUtils.getJsonString(object, parent != null ? "postId" : "threadId"));
		if (parent != null) post.setParentPostNumber(parent);
		post.setSticky(flag(object, "pinned"));
		post.setClosed(flag(object, "locked") || flag(object, "archived"));
		post.setCyclical(flag(object, "cyclic"));
		try {
			post.setTimestamp(Instant.parse(object.getString("creation")).toEpochMilli());
		} catch (DateTimeParseException e) {
			throw new JSONException("Invalid Kohlchan timestamp");
		}
		String name = text(object, "name");
		if (name != null) {
			int index = name.indexOf('#');
			if (index >= 0) {
				post.setTripcode(name.substring(index).replace('#', '!'));
				name = StringUtils.nullIfEmpty(name.substring(0, index));
			}
			post.setName(name);
		}
		post.setIdentifier(text(object, "id"));
		post.setCapcode(text(object, "signedRole"));
		post.setSubject(text(object, "subject"));
		String email = text(object, "email");
		if ("sage".equals(email)) post.setSage(true);
		else post.setEmail(email);
		String icon = CommonUtils.optJsonString(object, "flag");
		if (icon != null && icon.startsWith("/") && !icon.startsWith("//")) {
			post.setIcons(new Icon(locator, locator.buildPath(icon), text(object, "flagName")));
		}
		// Kohlchan supplies rendered HTML as "markdown". Preserve its actual quote URLs;
		// in particular, a cross-thread quote must not be rewritten into the current thread.
		post.setComment(CommonUtils.getJsonString(object, "markdown"));
		post.setCommentMarkup(CommonUtils.optJsonString(object, "message"));
		JSONArray files = object.optJSONArray("files");
		if (files != null) {
			ArrayList<FileAttachment> attachments = new ArrayList<>();
			for (int i = 0; i < files.length(); i++) {
				JSONObject file = files.getJSONObject(i);
				String path = CommonUtils.optJsonString(file, "path");
				if (path == null || !path.startsWith("/.media/")) continue;
				FileAttachment attachment = new FileAttachment();
				attachment.setFileUri(locator, locator.buildPath(path));
				String thumb = CommonUtils.optJsonString(file, "thumb");
				if (thumb != null && thumb.startsWith("/") && !thumb.startsWith("//")) {
					if (thumb.toLowerCase(java.util.Locale.US).contains("spoiler")) {
						attachment.setSpoiler(true);
					} else {
						attachment.setThumbnailUri(locator, locator.buildPath(thumb));
					}
				}
				attachment.setOriginalName(text(file, "originalName"));
				attachment.setSize(Math.max(0, file.optInt("size")));
				attachment.setWidth(Math.max(0, file.optInt("width")));
				attachment.setHeight(Math.max(0, file.optInt("height")));
				attachments.add(attachment);
			}
			post.setAttachments(attachments);
		}
		return post;
	}

	static Posts createPosts(JSONObject object, KohlchanChanLocator locator) throws JSONException {
		Post original = createPost(object, locator, null);
		ArrayList<Post> posts = new ArrayList<>();
		posts.add(original);
		JSONArray replies = object.optJSONArray("posts");
		if (replies != null) {
			for (int i = 0; i < replies.length(); i++) {
				posts.add(createPost(replies.getJSONObject(i), locator, original.getPostNumber()));
			}
		}
		return new Posts(posts).setUniquePosters(object.optInt("uniquePosters"));
	}

	static Posts[] createThreads(JSONArray array, KohlchanChanLocator locator) throws JSONException {
		Posts[] threads = new Posts[array.length()];
		for (int i = 0; i < threads.length; i++) {
			JSONObject object = array.getJSONObject(i);
			Posts posts = createPosts(object, locator);
			int count = posts.getPosts().length;
			int total = object.has("postCount") ? object.optInt("postCount") + 1
					: count + Math.max(0, object.optInt("omittedPosts"));
			posts.addPostsCount(Math.max(count, total));
			threads[i] = posts;
		}
		return threads;
	}
}
