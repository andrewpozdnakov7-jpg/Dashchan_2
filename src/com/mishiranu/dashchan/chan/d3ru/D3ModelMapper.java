package com.mishiranu.dashchan.chan.d3ru;

import android.net.Uri;
import android.text.TextUtils;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

public final class D3ModelMapper {
	private static final String BASE_URI = "https://d3.ru/";
	private static final Safelist BODY_SAFELIST = Safelist.relaxed().addTags("s", "strike");

	private D3ModelMapper() {}

	private static String getString(JSONObject object, String name) {
		return object != null ? CommonUtils.optJsonString(object, name) : null;
	}

	private static Uri getHttpsUri(String value) {
		if (StringUtils.isEmpty(value)) return null;
		Uri uri = Uri.parse(value.trim());
		return "https".equalsIgnoreCase(uri.getScheme()) && !StringUtils.isEmpty(uri.getHost()) ? uri : null;
	}

	private static String getFileName(Uri uri, String fallback) {
		String name = uri != null ? uri.getLastPathSegment() : null;
		return StringUtils.isEmpty(name) ? fallback : name;
	}

	private static FileAttachment createAttachment(D3ChanLocator locator, Uri fileUri, Uri thumbnailUri,
			String fallbackName, int width, int height) {
		if (fileUri == null) return null;
		FileAttachment attachment = new FileAttachment().setFileUri(locator, fileUri)
				.setOriginalName(getFileName(fileUri, fallbackName)).setWidth(width).setHeight(height);
		if (thumbnailUri != null) attachment.setThumbnailUri(locator, thumbnailUri);
		return attachment;
	}

	private static Uri getThumbnailUri(JSONObject media) {
		JSONObject thumbnails = media != null ? media.optJSONObject("thumbnails") : null;
		if (thumbnails == null) return null;
		String[] sizes = {"width_500", "width_330", "width_700", "width_120", "original"};
		for (String size : sizes) {
			JSONObject thumbnail = thumbnails.optJSONObject(size);
			Uri uri = getHttpsUri(getString(thumbnail, "url"));
			if (uri != null) return uri;
		}
		return null;
	}

	private static void addMedia(JSONObject media, D3ChanLocator locator, String fallbackName,
			LinkedHashMap<String, FileAttachment> attachments) {
		if (media == null) return;
		Uri fileUri = getHttpsUri(getString(media, "url"));
		if (fileUri == null) {
			JSONObject thumbnails = media.optJSONObject("thumbnails");
			JSONObject original = thumbnails != null ? thumbnails.optJSONObject("original") : null;
			fileUri = getHttpsUri(getString(original, "url"));
		}
		if (fileUri == null) return;
		FileAttachment attachment = createAttachment(locator, fileUri, getThumbnailUri(media), fallbackName,
				Math.max(0, media.optInt("width")), Math.max(0, media.optInt("height")));
		if (attachment != null) attachments.putIfAbsent(fileUri.toString(), attachment);
	}

	private static void addMedia(Object media, D3ChanLocator locator, String fallbackName,
			LinkedHashMap<String, FileAttachment> attachments) {
		if (media instanceof JSONObject) {
			addMedia((JSONObject) media, locator, fallbackName, attachments);
		} else if (media instanceof JSONArray) {
			JSONArray array = (JSONArray) media;
			for (int i = 0; i < array.length(); i++) {
				addMedia(array.optJSONObject(i), locator, fallbackName + '-' + (i + 1), attachments);
			}
		}
	}

	private static String getLinkUrl(JSONObject data) {
		if (data == null) return null;
		Object link = data.opt("link");
		if (link instanceof JSONObject) return getString((JSONObject) link, "url");
		return link instanceof String ? (String) link : null;
	}

	private static String sanitizeBody(String source, D3ChanLocator locator, String fallbackName,
			LinkedHashMap<String, FileAttachment> attachments) {
		if (StringUtils.isEmpty(source)) return null;
		String cleaned = Jsoup.clean(source, BASE_URI, BODY_SAFELIST);
		Document document = Jsoup.parseBodyFragment(cleaned, BASE_URI);
		Element body = document.body();
		for (Element image : body.select("img[src]")) {
			Uri uri = getHttpsUri(image.absUrl("src"));
			if (uri != null) {
				FileAttachment attachment = createAttachment(locator, uri, uri, fallbackName,
						Math.max(0, parseInteger(image.attr("width"))),
						Math.max(0, parseInteger(image.attr("height"))));
				if (attachment != null) attachments.putIfAbsent(uri.toString(), attachment);
			}
			image.remove();
		}
		for (Element link : body.select("a[href]")) {
			Uri uri = getHttpsUri(link.absUrl("href"));
			if (uri != null) {
				link.attr("href", uri.toString());
			} else {
				link.unwrap();
			}
		}
		String html = body.html().trim();
		return StringUtils.nullIfEmpty(html);
	}

	private static int parseInteger(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void setUser(Post post, JSONObject object) {
		JSONObject user = object.optJSONObject("user");
		String name = getString(user, "login");
		post.setName(StringUtils.isEmpty(name) ? "Пользователь d3.ru" : name);
	}

	private static void setCommon(Post post, JSONObject object) {
		long created = object.optLong("created");
		if (created > 0) post.setTimestamp(created * 1000L);
		setUser(post, object);
		if (object.has("rating") && !object.isNull("rating")) {
			int rating = object.optInt("rating");
			int userVote = object.has("user_vote") && !object.isNull("user_vote")
					? object.optInt("user_vote") : 0;
			post.setVote(Math.max(rating, 0), Math.max(-rating, 0), userVote);
		}
	}

	public static Post createThreadPost(JSONObject object, D3ChanLocator locator) {
		long id = object != null ? object.optLong("id") : 0;
		if (id <= 0) return null;
		String number = Long.toString(id);
		Post post = new Post().setPostNumber(number);
		setCommon(post, object);
		post.setSubject(getString(object, "title"));
		post.setSticky(object.optBoolean("pinned"));

		LinkedHashMap<String, FileAttachment> attachments = new LinkedHashMap<>();
		JSONObject data = object.optJSONObject("data");
		if (data != null) {
			addMedia(data.opt("media"), locator, "d3-" + number, attachments);
			Object link = data.opt("link");
			if (link instanceof JSONObject) {
				JSONObject linkObject = (JSONObject) link;
				addMedia(linkObject.opt("media"), locator, "d3-" + number + "-link", attachments);
				String type = getString(linkObject, "type");
				if ("image".equals(type)) {
					Uri fileUri = getHttpsUri(getString(linkObject, "url"));
					FileAttachment attachment = createAttachment(locator, fileUri,
							getThumbnailUri(linkObject), "d3-" + number + "-link", 0, 0);
					if (attachment != null) attachments.putIfAbsent(fileUri.toString(), attachment);
				}
			}
			String source = getString(data, "text");
			if (StringUtils.isEmpty(source)) source = getString(data, "snippet");
			post.setComment(sanitizeBody(source, locator, "d3-" + number + "-inline", attachments));
			String linkUrl = getLinkUrl(data);
			Uri linkUri = getHttpsUri(linkUrl);
			if (linkUri != null && !attachments.containsKey(linkUri.toString())) {
				String host = linkUri.getHost();
				String linkHtml = "<a href=\"" + TextUtils.htmlEncode(linkUri.toString()) + "\">"
						+ TextUtils.htmlEncode(host != null ? host : linkUri.toString()) + "</a>";
				String comment = post.getComment();
				post.setComment(StringUtils.isEmpty(comment) ? linkHtml : comment + "<br>" + linkHtml);
			}
		}
		if (attachments.isEmpty()) {
			Uri mainImage = getHttpsUri(getString(object, "main_image_url"));
			FileAttachment attachment = createAttachment(locator, mainImage, mainImage,
					"d3-" + number, 0, 0);
			if (attachment != null) attachments.put(mainImage.toString(), attachment);
		}
		if (!attachments.isEmpty()) post.setAttachments(new ArrayList<>(attachments.values()));
		return post;
	}

	public static Post createComment(JSONObject object, D3ChanLocator locator, String threadNumber) {
		long id = object != null ? object.optLong("id") : 0;
		if (id <= 0) return null;
		String number = Long.toString(id);
		Post post = new Post().setPostNumber(number).setThreadNumber(threadNumber);
		long parentId = object.optLong("parent_id");
		post.setParentPostNumber(parentId > 0 ? Long.toString(parentId) : threadNumber);
		setCommon(post, object);
		LinkedHashMap<String, FileAttachment> attachments = new LinkedHashMap<>();
		String body = sanitizeBody(getString(object, "body"), locator,
				"d3-comment-" + number, attachments);
		if (StringUtils.isEmpty(body) && object.optBoolean("deleted")) body = "<i>Комментарий удалён</i>";
		post.setComment(body);
		if (!attachments.isEmpty()) post.setAttachments(new ArrayList<>(attachments.values()));
		return post;
	}
}
