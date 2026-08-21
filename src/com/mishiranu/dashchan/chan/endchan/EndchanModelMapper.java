package com.mishiranu.dashchan.chan.endchan;

import android.net.Uri;
import chan.content.model.FileAttachment;
import chan.content.model.Icon;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class EndchanModelMapper {
	private EndchanModelMapper() {}

	private static boolean getBoolean(JSONObject jsonObject, String name) {
		return jsonObject.optBoolean(name, false) || jsonObject.optInt(name, 0) != 0;
	}

	private static Uri buildCatalogFileUri(String thumb, EndchanChanLocator locator) {
		if (StringUtils.isEmpty(thumb)) return null;
		String path = thumb;
		int slash = path.lastIndexOf('/');
		String fileName = slash >= 0 ? path.substring(slash + 1) : path;
		if (fileName.startsWith("t_")) {
			String baseName = fileName.substring(2);
			int dash = baseName.lastIndexOf('-');
			if (dash >= 0) {
				String encodedMime = baseName.substring(dash + 1);
				String extension = null;
				for (String prefix : new String[] {"image", "video", "audio"}) {
					if (encodedMime.startsWith(prefix) && encodedMime.length() > prefix.length()) {
						extension = encodedMime.substring(prefix.length());
						break;
					}
				}
				if ("jpeg".equals(extension)) extension = "jpg";
				if (!StringUtils.isEmpty(extension)) {
					path = (slash >= 0 ? path.substring(0, slash + 1) : "") + baseName + "." + extension;
				}
			}
		}
		return locator.buildPath(path);
	}

	public static FileAttachment createFileAttachment(JSONObject jsonObject, EndchanChanLocator locator)
			throws JSONException {
		FileAttachment attachment = new FileAttachment();
		attachment.setSize(jsonObject.optInt("size"));
		attachment.setWidth(jsonObject.optInt("width"));
		attachment.setHeight(jsonObject.optInt("height"));
		String path = CommonUtils.optJsonString(jsonObject, "path");
		String thumb = CommonUtils.optJsonString(jsonObject, "thumb");
		if (StringUtils.isEmpty(path)) {
			Uri catalogUri = buildCatalogFileUri(thumb, locator);
			if (catalogUri == null) throw new JSONException("Missing attachment path");
			attachment.setFileUri(locator, catalogUri);
		} else {
			attachment.setFileUri(locator, locator.buildPath(path));
		}
		if ("/spoiler.png".equals(thumb)) {
			attachment.setSpoiler(true);
		} else if (!StringUtils.isEmpty(thumb)) {
			attachment.setThumbnailUri(locator, locator.buildPath(thumb));
		}
		String originalName = CommonUtils.optJsonString(jsonObject, "originalName");
		if (!StringUtils.isEmpty(originalName)) {
			attachment.setOriginalName(StringUtils.clearHtml(originalName));
		}
		return attachment;
	}

	private static final Pattern PATTERN_BROKEN_LINK = Pattern.compile("(<a [^>]*?href=\"/[^/]+/res/)"
			+ "(\\d+)(\\.html#\\2\")");
	private static final Pattern PATTERN_COLORED_TEXT = Pattern.compile("<span class=\"(\\w+)Text\">");

	private static long parseTimestamp(String value) throws JSONException {
		try {
			return Instant.parse(value).toEpochMilli();
		} catch (DateTimeParseException e) {
			JSONException exception = new JSONException("Invalid Endchan timestamp");
			exception.initCause(e);
			throw exception;
		}
	}

	public static Post createPost(JSONObject jsonObject, EndchanChanLocator locator, String threadNumber)
			throws JSONException {
		Post post = new Post();
		post.setSticky(getBoolean(jsonObject, "pinned"));
		post.setClosed(getBoolean(jsonObject, "locked"));
		post.setCyclical(getBoolean(jsonObject, "cyclic"));
		if (threadNumber != null) {
			post.setParentPostNumber(threadNumber);
			post.setPostNumber(CommonUtils.getJsonString(jsonObject, "postId"));
		} else {
			post.setPostNumber(CommonUtils.getJsonString(jsonObject, "threadId"));
		}
		post.setTimestamp(parseTimestamp(CommonUtils.getJsonString(jsonObject, "creation")));
		String name = CommonUtils.optJsonString(jsonObject, "name");
		if (!StringUtils.isEmpty(name)) {
			name = StringUtils.clearHtml(name).trim();
			int index = name.indexOf('#');
			if (index >= 0) {
				post.setTripcode(name.substring(index).replace('#', '!'));
				name = index > 0 ? name.substring(0, index) : null;
			}
			post.setName(StringUtils.nullIfEmpty(name));
		}
		String identifier = CommonUtils.optJsonString(jsonObject, "id");
		if (!StringUtils.isEmpty(identifier)) {
			post.setIdentifier(StringUtils.nullIfEmpty(StringUtils.clearHtml(identifier).trim()));
		}
		String signedRole = CommonUtils.optJsonString(jsonObject, "signedRole");
		if (!StringUtils.isEmpty(signedRole)) {
			post.setCapcode(StringUtils.nullIfEmpty(StringUtils.clearHtml(signedRole).trim()));
		}
		String email = CommonUtils.optJsonString(jsonObject, "email");
		if ("sage".equals(email)) {
			post.setSage(true);
		} else if (!StringUtils.isEmpty(email)) {
			post.setEmail(StringUtils.nullIfEmpty(StringUtils.clearHtml(email).trim()));
		}
		String flag = CommonUtils.optJsonString(jsonObject, "flag");
		if (!StringUtils.isEmpty(flag)) {
			Uri uri = locator.buildPath(flag);
			String flagName = CommonUtils.optJsonString(jsonObject, "flagName");
			if (StringUtils.isEmpty(flagName)) {
				flagName = StringUtils.emptyIfNull(uri.getLastPathSegment());
				int dot = flagName.indexOf('.');
				if (dot >= 0) flagName = flagName.substring(0, dot);
				flagName = flagName.toLowerCase(Locale.US);
			} else {
				flagName = StringUtils.clearHtml(flagName);
			}
			post.setIcons(new Icon(locator, uri, flagName));
		}
		String subject = CommonUtils.optJsonString(jsonObject, "subject");
		if (!StringUtils.isEmpty(subject)) {
			post.setSubject(StringUtils.nullIfEmpty(StringUtils.clearHtml(subject).trim()));
		}
		String comment = CommonUtils.getJsonString(jsonObject, "markdown");
		if (!StringUtils.isEmpty(comment)) {
			comment = comment.replaceAll("(<a class=\"quoteLink\".*?>)&gt&gt", "$1&gt;&gt;");
			String fixedThreadNumber = threadNumber;
			comment = StringUtils.replaceAll(comment, PATTERN_BROKEN_LINK,
					matcher -> matcher.group(1) + (fixedThreadNumber != null ? fixedThreadNumber : matcher.group(2))
							+ matcher.group(3));
			comment = StringUtils.replaceAll(comment, PATTERN_COLORED_TEXT, matcher -> {
				String color = matcher.group(1);
				switch (color) {
					case "green":
					case "red": return matcher.group();
					case "redMeme": color = "#af0a0f"; break;
					case "meme": color = "#ff0000"; break;
					case "autism": color = "#aa44ff"; break;
					case "orange": color = "#ffaa00"; break;
					case "pink": color = "#ff66bb"; break;
					case "brown": color = "#aa6600"; break;
				}
				return "<span colored=\"true\" style=\"color: " + color + "\">";
			});
		}
		post.setComment(comment);
		post.setCommentMarkup(CommonUtils.optJsonString(jsonObject, "message"));
		JSONArray files = jsonObject.optJSONArray("files");
		if (files != null && files.length() > 0) {
			ArrayList<FileAttachment> attachments = new ArrayList<>();
			for (int i = 0; i < files.length(); i++) {
				attachments.add(createFileAttachment(files.getJSONObject(i), locator));
			}
			post.setAttachments(attachments);
		} else {
			String thumb = CommonUtils.optJsonString(jsonObject, "thumb");
			if (!StringUtils.isEmpty(thumb)) {
				JSONObject attachmentObject = new JSONObject();
				attachmentObject.put("thumb", thumb);
				post.setAttachments(new FileAttachment[] {createFileAttachment(attachmentObject, locator)});
			}
		}
		return post;
	}

	public static Posts createPosts(JSONObject jsonObject, EndchanChanLocator locator) throws JSONException {
		Post originalPost = createPost(jsonObject, locator, null);
		JSONArray jsonArray = jsonObject.optJSONArray("posts");
		ArrayList<Post> posts = new ArrayList<>(1 + (jsonArray != null ? jsonArray.length() : 0));
		posts.add(originalPost);
		if (jsonArray != null) {
			String threadNumber = originalPost.getPostNumber();
			for (int i = 0; i < jsonArray.length(); i++) {
				posts.add(createPost(jsonArray.getJSONObject(i), locator, threadNumber));
			}
		}
		return new Posts(posts);
	}

	public static Posts[] createThreads(JSONArray jsonArray, EndchanChanLocator locator) throws JSONException {
		if (jsonArray == null || jsonArray.length() == 0) return null;
		Posts[] threads = new Posts[jsonArray.length()];
		for (int i = 0; i < jsonArray.length(); i++) {
			JSONObject jsonObject = jsonArray.getJSONObject(i);
			Posts posts = createPosts(jsonObject, locator);
			int totalCount;
			if (jsonObject.has("postCount")) {
				totalCount = jsonObject.optInt("postCount") + 1;
			} else if (jsonObject.has("omittedPosts")) {
				totalCount = jsonObject.optInt("omittedPosts") + posts.getPosts().length;
			} else {
				totalCount = posts.getPosts().length;
			}
			posts.addPostsCount(totalCount);
			threads[i] = posts;
		}
		return threads;
	}
}
