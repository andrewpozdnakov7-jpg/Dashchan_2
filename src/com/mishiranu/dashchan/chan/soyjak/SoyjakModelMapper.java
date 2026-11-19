package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SoyjakModelMapper {
	private static final Pattern MEDIA_ID = Pattern.compile("[a-zA-Z0-9_-]+");
	private static final Pattern EXTENSION = Pattern.compile("\\.[a-zA-Z0-9]+");
	private static final Pattern THUMBNAIL = Pattern.compile("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+");

	private SoyjakModelMapper() {}

	static Posts readCatalogEntry(JSONObject starter, SoyjakChanLocator locator, String boardName) throws JSONException {
		String threadNumber = readNumber(starter);
		return threadPreview(starter, Collections.singletonList(readPost(starter, locator, boardName, threadNumber, true)));
	}

	static Posts readPreview(JSONArray posts, SoyjakChanLocator locator, String boardName) throws JSONException {
		JSONObject starter = posts.getJSONObject(0);
		return threadPreview(starter, readPosts(posts, locator, boardName, readNumber(starter)));
	}

	private static Posts threadPreview(JSONObject starter, List<Post> posts) {
		int postsCount = Math.max(posts.size(), starter.optInt("replies") + 1);
		int filesCount = posts.get(0).getAttachmentsCount() + Math.max(0, starter.optInt("images"))
				+ Math.max(0, starter.optInt("omitted_images"));
		return new Posts(posts).addPostsCount(postsCount).addFilesCount(filesCount);
	}

	static ArrayList<Post> readPosts(JSONArray entries, SoyjakChanLocator locator, String boardName,
			String threadNumber) throws JSONException {
		if (entries.length() == 0) {
			throw new JSONException("Thread has no original post");
		}
		ArrayList<Post> posts = new ArrayList<>(entries.length());
		for (int i = 0; i < entries.length(); i++) {
			posts.add(readPost(entries.getJSONObject(i), locator, boardName, threadNumber, i == 0));
		}
		return posts;
	}

	private static String readNumber(JSONObject value) throws JSONException {
		long number = value.getLong("no");
		if (number <= 0 || number > Integer.MAX_VALUE) {
			throw new JSONException("Invalid post number");
		}
		return Long.toString(number);
	}

	private static Post readPost(JSONObject value, SoyjakChanLocator locator, String boardName,
			String threadNumber, boolean original) throws JSONException {
		String number = readNumber(value);
		if (original ? !threadNumber.equals(number) || value.optLong("resto") != 0
				: !threadNumber.equals(value.optString("resto")) || threadNumber.equals(number)) {
			throw new JSONException("Post does not belong to the requested thread");
		}
		String email = value.optString("email");
		Post post = new Post().setPostNumber(number).setThreadNumber(threadNumber)
				.setParentPostNumber(original ? null : threadNumber)
				.setTimestamp(value.optLong("time") * 1000L)
				.setName(StringUtils.clearHtml(value.optString("name")).trim())
				.setSubject(StringUtils.clearHtml(value.optString("sub")).trim())
				.setComment(StringUtils.linkify(value.optString("com")))
				.setIdentifier(value.optString("id")).setTripcode(value.optString("trip"))
				.setCapcode(value.optString("capcode")).setSage("sage".equalsIgnoreCase(email))
				.setEmail("sage".equalsIgnoreCase(email) ? null : email)
				.setSticky(flag(value, "sticky")).setClosed(flag(value, "locked") || flag(value, "closed"))
				.setCyclical(flag(value, "cyclical"));
		ArrayList<FileAttachment> attachments = new ArrayList<>();
		addAttachment(attachments, value, locator, boardName);
		JSONArray extra = value.optJSONArray("extra_files");
		if (extra != null) {
			for (int i = 0; i < extra.length(); i++) {
				JSONObject file = extra.optJSONObject(i);
				if (file != null) {
					addAttachment(attachments, file, locator, boardName);
				}
			}
		}
		return post.setAttachments(attachments);
	}

	private static void addAttachment(ArrayList<FileAttachment> attachments, JSONObject file,
			SoyjakChanLocator locator, String boardName) {
		String mediaId = file.optString("tim");
		String extension = file.optString("ext");
		if (flag(file, "filedeleted") || flag(file, "pending_approval") || !MEDIA_ID.matcher(mediaId).matches()
				|| !EXTENSION.matcher(extension).matches()) {
			return;
		}
		String thumb = file.optString("thumb");
		String name = StringUtils.clearHtml(file.optString("filename"));
		// The API also uses "file" and "spoiler" in place of thumbnail names.
		Uri thumbnailUri = THUMBNAIL.matcher(thumb).matches() ? locator.buildPath(boardName, "thumb", thumb) : null;
		attachments.add(new FileAttachment().setFileUri(locator, locator.buildPath(boardName, "src", mediaId + extension))
				.setThumbnailUri(locator, thumbnailUri)
				.setOriginalName(StringUtils.isEmpty(name) ? null : name + extension)
				.setSize(Math.max(0, file.optInt("fsize")))
				.setWidth(Math.max(0, file.optInt("w"))).setHeight(Math.max(0, file.optInt("h")))
				.setSpoiler(flag(file, "spoiler")));
	}

	private static boolean flag(JSONObject value, String name) {
		return value.optBoolean(name) || value.optInt(name) != 0;
	}
}
