package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import android.util.Xml;
import chan.content.model.Attachment;
import chan.content.model.Board;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.StringUtils;
import java.io.IOException;
import java.io.StringReader;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

final class PikabuHtmlParser {
	private static final int MAX_COMMUNITIES = 200;
	private static final int MAX_TAGS = 200;
	private static final int MAX_AUTHORS = 50;
	private static final int MAX_STORY_TAGS = 12;
	private static final Pattern NUMBER = Pattern.compile("\\d+");
	private static final Pattern META_VALUE = Pattern.compile("(?:^|;)([a-z]+)=([^;]*)");
	private static final Pattern STORY_PATH = Pattern.compile("/story/(?:[^/?#]*_)?(\\d+)/?");
	private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\.(?:jpe?g|png|gif|webp|bmp|svg|"
			+ "mp4|webm|mkv|mov|m4v|mp3|ogg|wav|flac)(?:$|[?#])");
	private static final DateTimeFormatter XML_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",
			Locale.US);
	private static final ZoneId PIKABU_TIME_ZONE = ZoneId.of("Europe/Moscow");

	private PikabuHtmlParser() {}

	public static SessionData parseSessionData(String html) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		Element element = document.selectFirst("script.app__config[data-entry=initParams]");
		if (element == null) return null;
		try {
			JSONObject object = new JSONObject(element.data());
			long userId = object.optLong("userID", 0L);
			boolean deleted = object.optBoolean("isDeleted", false);
			String userName = object.optString("userName");
			if (StringUtils.isEmpty(userName)) userName = object.optString("username");
			String csrfToken = object.optString("csrfToken");
			return new SessionData(userId > 0L && !deleted, userName, csrfToken);
		} catch (JSONException e) {
			return null;
		}
	}

	public static final class SessionData {
		public final boolean authorized;
		public final String userName;
		public final String csrfToken;

		private SessionData(boolean authorized, String userName, String csrfToken) {
			this.authorized = authorized;
			this.userName = userName;
			this.csrfToken = csrfToken;
		}
	}

	public static boolean isBoardName(String boardName) {
		return PikabuChanLocator.isSupportedBoardName(boardName);
	}

	public static ParsedThreads parseThreads(String html, PikabuChanLocator locator, String boardName) {
		return parseFeed(html, locator, locator.getExpectedFeedMode(boardName));
	}

	public static ParsedThreads parseSearch(String html, PikabuChanLocator locator, String searchQuery) {
		return parseFeed(html, locator, locator.getExpectedSearchFeedMode(searchQuery));
	}

	private static ParsedThreads parseFeed(String html, PikabuChanLocator locator, String expectedMode) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		Element feed = document.selectFirst("div.stories-feed[data-mode]");
		ArrayList<Posts> threads = new ArrayList<>();
		boolean validPage = feed != null && expectedMode.equals(feed.attr("data-mode"));
		if (validPage) {
			for (Element article : feed.select("div.stories-feed__container article.story[data-story-id]")) {
				Post post = parseStory(article, locator);
				if (post == null) continue;
				int comments = parseInteger(article.attr("data-comments"), 0);
				int files = post.getAttachmentsCount();
				threads.add(new Posts(post).addPostsCount(comments + 1).addFilesCount(files)
						.addPostsWithFilesCount(files > 0 ? 1 : 0));
			}
		}
		int pagesCount = feed != null ? parseInteger(feed.attr("data-page-last"), 0) : 0;
		String title = null;
		String description = null;
		if ("community".equals(expectedMode)) {
			Element titleElement = document.selectFirst("div.community-header__title");
			Element descriptionElement = document.selectFirst("div.community-header__description, "
					+ "div.community-header__about");
			title = titleElement != null ? StringUtils.nullIfEmpty(titleElement.text().trim()) : null;
			description = descriptionElement != null
					? StringUtils.nullIfEmpty(descriptionElement.text().trim()) : null;
		} else if ("profile".equals(expectedMode)) {
			Element titleElement = document.selectFirst("h1.profile__nick");
			Element descriptionElement = document.selectFirst("span.profile__user-about-content");
			title = titleElement != null ? StringUtils.nullIfEmpty(titleElement.text().trim()) : null;
			description = descriptionElement != null
					? StringUtils.nullIfEmpty(descriptionElement.text().trim()) : null;
		}
		return new ParsedThreads(threads, pagesCount, validPage, title, description);
	}

	public static ArrayList<Board> parseCommunities(String html) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
		for (Element item : document.select("div.community__inner")) {
			Element link = item.selectFirst("div.community__title a[href]");
			if (link == null) continue;
			Uri uri = resolvePageUri(link, link.attr("href"));
			if (!isPikabuPageUri(uri)) continue;
			List<String> segments = uri != null ? uri.getPathSegments() : null;
			if (segments == null || segments.size() != 2 || !"community".equals(segments.get(0))) continue;
			String boardName = PikabuChanLocator.createCommunityBoardName(segments.get(1));
			String title = link.text().trim();
			if (boardName == null || title.isEmpty()) continue;
			Element information = item.selectFirst("div.community__information");
			String description = information != null ? information.text().trim() : null;
			boards.putIfAbsent(boardName, new Board(boardName, title, StringUtils.nullIfEmpty(description)));
			if (boards.size() >= MAX_COMMUNITIES) break;
		}
		return new ArrayList<>(boards.values());
	}

	public static ArrayList<Board> parseTags(String html) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
		for (Element link : document.select("a.tags__tag[href]")) {
			Uri uri = resolvePageUri(link, link.attr("href"));
			if (!isPikabuPageUri(uri)) continue;
			List<String> segments = uri != null ? uri.getPathSegments() : null;
			if (segments == null || segments.size() < 2 || !"tag".equals(segments.get(0))) continue;
			String tag = segments.get(1).trim();
			String boardName = PikabuChanLocator.createTagBoardName(tag);
			Element copy = link.clone();
			Element countElement = copy.selectFirst("span.tags__tag-count");
			String count = countElement != null ? countElement.text().trim() : null;
			if (countElement != null) countElement.remove();
			String title = copy.text().trim();
			if (boardName == null || title.isEmpty()) continue;
			String description = !StringUtils.isEmpty(count) ? count + " историй" : null;
			boards.putIfAbsent(boardName, new Board(boardName, title, description));
			if (boards.size() >= MAX_TAGS) break;
		}
		return new ArrayList<>(boards.values());
	}

	public static ArrayList<Board> parsePopularAuthors(String html) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
		for (Element link : document.select("a[href]")) {
			Uri uri = resolvePageUri(link, link.attr("href"));
			if (!isPikabuPageUri(uri)) continue;
			List<String> segments = uri != null ? uri.getPathSegments() : null;
			if (segments == null || segments.size() != 1 || !segments.get(0).startsWith("@")) continue;
			String profile = segments.get(0).substring(1);
			String boardName = PikabuChanLocator.createProfileBoardName(profile);
			if (boardName == null) continue;
			String title = link.text().trim();
			if (title.isEmpty()) title = "@" + profile;
			boards.putIfAbsent(boardName, new Board(boardName, title, "Публикации автора"));
			if (boards.size() >= MAX_AUTHORS) break;
		}
		return new ArrayList<>(boards.values());
	}

	public static ParsedPosts parsePosts(String html, PikabuChanLocator locator, String threadNumber) {
		Document document = Jsoup.parse(html, "https://pikabu.ru/");
		Element article = document.selectFirst("article.story[data-story-id=" + threadNumber + "][data-page=true]");
		if (article == null) {
			for (Element candidate : document.select("article.story[data-story-id]")) {
				if (threadNumber.equals(candidate.attr("data-story-id"))) {
					article = candidate;
					break;
				}
			}
		}
		if (article == null) return new ParsedPosts(new Posts(), false, false, -1, new ArrayList<>());

		Post originalPost = parseStory(article, locator);
		if (originalPost == null) return new ParsedPosts(new Posts(), false, false, -1, new ArrayList<>());
		originalPost.setThreadNumber(threadNumber).setOriginalPoster(true);
		LinkedHashMap<String, Post> posts = new LinkedHashMap<>();
		posts.put(threadNumber, originalPost);
		for (Element element : document.select("div.comments__container div.comment[data-id]")) {
			Post post = parseComment(element, locator, threadNumber);
			if (post != null) posts.putIfAbsent(post.getPostNumber(), post);
		}
		int expectedComments = parseInteger(article.attr("data-comments"), -1);
		boolean fullThread = expectedComments >= 0 && posts.size() - 1 >= expectedComments;
		return new ParsedPosts(new Posts(posts.values()), true, fullThread, expectedComments,
				parseCommentTreeIds(document));
	}

	private static ArrayList<String> parseCommentTreeIds(Document document) {
		Element script = document.selectFirst("script[type=application/json][data-entry=comments-tree]");
		if (script == null) return new ArrayList<>();
		String json = script.data();
		if (StringUtils.isEmpty(json)) json = script.html();
		try {
			JSONObject object = new JSONObject(json);
			long minimum = object.optLong("min", -1L);
			JSONArray tree = object.optJSONArray("tree");
			if (minimum < 0L || tree == null) return new ArrayList<>();
			LinkedHashSet<String> ids = new LinkedHashSet<>();
			collectCommentTreeIds(tree, minimum, ids);
			return new ArrayList<>(ids);
		} catch (JSONException e) {
			return new ArrayList<>();
		}
	}

	private static void collectCommentTreeIds(JSONArray tree, long minimum, LinkedHashSet<String> ids)
			throws JSONException {
		for (int i = 0; i < tree.length(); i++) {
			JSONArray node = tree.optJSONArray(i);
			if (node == null || node.length() == 0) continue;
			Object offsetValue = node.opt(0);
			Long offset = parseLong(offsetValue);
			if (offset != null) {
				long id = minimum + offset;
				if (id > 0L) ids.add(Long.toString(id));
			}
			JSONArray children = node.optJSONArray(2);
			if (children != null) collectCommentTreeIds(children, minimum, ids);
		}
	}

	private static Long parseLong(Object value) {
		if (value instanceof Number) return ((Number) value).longValue();
		if (value instanceof String) {
			try {
				return Long.parseLong((String) value);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	public static ArrayList<Post> parseCommentsJson(String json, PikabuChanLocator locator,
			String threadNumber) {
		try {
			JSONObject object = new JSONObject(json);
			if (!object.optBoolean("result", false)) return null;
			JSONArray data = object.optJSONArray("data");
			if (data == null) return null;
			ArrayList<Post> posts = new ArrayList<>();
			for (int i = 0; i < data.length(); i++) {
				JSONObject item = data.optJSONObject(i);
				if (item == null) continue;
				String id = item.optString("id", "").trim();
				String html = item.optString("html", "");
				if (!NUMBER.matcher(id).matches() || StringUtils.isEmpty(html)) continue;
				Document document = Jsoup.parseBodyFragment(html, "https://pikabu.ru/");
				Element element = document.selectFirst("div.comment[data-id=" + id + "]");
				if (element == null) continue;
				Post post = parseComment(element, locator, threadNumber);
				if (post != null) posts.add(post);
			}
			return posts;
		} catch (JSONException e) {
			return null;
		}
	}

	public static ArrayList<Post> parseCommentsXml(String xml, PikabuChanLocator locator, String threadNumber) {
		try {
			XmlPullParser parser = Xml.newPullParser();
			parser.setInput(new StringReader(xml));
			ArrayList<Post> posts = new ArrayList<>();
			boolean validRoot = false;
			for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT;
					eventType = parser.next()) {
				if (eventType != XmlPullParser.START_TAG) continue;
				if (!validRoot) {
					if (!"comments".equals(parser.getName())) return null;
					validRoot = true;
					continue;
				}
				if (!"comment".equals(parser.getName())) continue;
				Post post = parseXmlComment(parser.getAttributeValue(null, "id"),
						parser.getAttributeValue(null, "answer"), parser.getAttributeValue(null, "nick"),
						parser.getAttributeValue(null, "date"), parser.getAttributeValue(null, "rating"),
						parser.nextText(), locator, threadNumber);
				if (post != null) posts.add(post);
			}
			return validRoot ? posts : null;
		} catch (IOException | XmlPullParserException e) {
			return null;
		}
	}

	private static Post parseStory(Element article, PikabuChanLocator locator) {
		String storyNumber = article.attr("data-story-id").trim();
		if (!NUMBER.matcher(storyNumber).matches()) return null;
		Element title = article.selectFirst("h1.story__title .story__title-link, "
				+ "h2.story__title .story__title-link, .story__title-link");
		if (title == null) return null;

		Post post = new Post().setThreadNumber(storyNumber).setPostNumber(storyNumber)
				.setSubject(StringUtils.nullIfEmpty(title.text().trim()));
		String author = article.attr("data-author-name").trim();
		if (author.isEmpty()) {
			Element authorElement = article.selectFirst("a.story__user-link[data-name], a.story__user-link .user__nick");
			if (authorElement != null) author = authorElement.hasAttr("data-name")
					? authorElement.attr("data-name").trim() : authorElement.text().trim();
		}
		post.setName(StringUtils.nullIfEmpty(author));
		Element time = article.selectFirst("time.story__datetime[datetime]");
		if (time != null) post.setTimestamp(parseTimestamp(time.attr("datetime")));

		Element content = article.selectFirst("div.story__content-inner");
		if (content != null) {
			Element clone = content.clone();
			ArrayList<Attachment> attachments = parseAttachments(clone, locator, storyNumber);
			if (!attachments.isEmpty()) post.setAttachments(attachments);
			cleanContent(clone, locator, storyNumber);
			appendStoryNavigation(clone, article, locator, author);
			post.setComment(StringUtils.nullIfEmpty(clone.html().trim()));
		}
		int rating = parseInteger(article.attr("data-rating"), 0);
		int userVote = normalizeVote(parseInteger(article.attr("data-vote"), 0));
		post.setVote(Math.max(rating, 0), Math.max(-rating, 0), userVote);
		return post;
	}

	private static void appendStoryNavigation(Element content, Element article, PikabuChanLocator locator,
			String author) {
		Element navigation = null;
		if (!StringUtils.isEmpty(author)) {
			String boardName = PikabuChanLocator.createProfileBoardName(author);
			if (boardName != null) {
				navigation = content.appendElement("p");
				navigation.appendElement("a").attr("href", locator.createBoardUri(boardName, 0).toString())
						.text("@" + author);
			}
		}
		Element community = article.selectFirst("a.story__community-link[href]");
		if (community != null) {
			Uri uri = resolvePageUri(community, community.attr("href"));
			if (isCommunityPageUri(uri) && !community.text().trim().isEmpty()) {
				if (navigation == null) navigation = content.appendElement("p");
				appendSeparator(navigation);
				navigation.appendElement("a").attr("href", uri.toString()).text(community.text().trim());
			}
		}
		int tags = 0;
		for (Element tag : article.select("a.tags__tag[data-tag][href]")) {
			String name = tag.attr("data-tag").trim();
			Uri uri = resolvePageUri(tag, tag.attr("href"));
			if (name.isEmpty() || !isTagPageUri(uri)) continue;
			if (navigation == null) navigation = content.appendElement("p");
			appendSeparator(navigation);
			navigation.appendElement("a").attr("href", uri.toString()).text("#" + name);
			if (++tags >= MAX_STORY_TAGS) break;
		}
	}

	private static void appendSeparator(Element element) {
		if (!element.childNodes().isEmpty()) element.appendText(" · ");
	}

	private static Post parseComment(Element element, PikabuChanLocator locator, String threadNumber) {
		String postNumber = element.attr("data-id").trim();
		if (!NUMBER.matcher(postNumber).matches()) return null;
		Map<String, String> meta = parseMeta(element.attr("data-meta"));
		String parent = meta.get("pid");
		if (StringUtils.isEmpty(parent) || !NUMBER.matcher(parent).matches() || "0".equals(parent)) {
			parent = threadNumber;
		}
		Post post = new Post().setThreadNumber(threadNumber).setPostNumber(postNumber)
				.setParentPostNumber(parent);
		Element author = element.selectFirst("a.comment__user[data-name], a.comment__user .user__nick");
		if (author != null) post.setName(author.hasAttr("data-name")
				? author.attr("data-name").trim() : author.text().trim());
		String date = meta.get("d");
		if (StringUtils.isEmpty(date)) {
			Element time = element.selectFirst("time.comment__datetime[datetime]");
			if (time != null) date = time.attr("datetime");
		}
		post.setTimestamp(parseTimestamp(date));

		Element content = element.selectFirst("div.comment__content");
		if (content != null) {
			Element clone = content.clone();
			ArrayList<Attachment> attachments = parseAttachments(clone, locator, postNumber);
			if (!attachments.isEmpty()) post.setAttachments(attachments);
			cleanContent(clone, locator, threadNumber);
			prependParentReference(clone, locator, threadNumber, postNumber, parent);
			post.setComment(StringUtils.nullIfEmpty(clone.html().trim()));
		}
		Element ratingElement = element.selectFirst("div.comment__rating-count");
		int rating = ratingElement != null ? parseInteger(ratingElement.text(), 0) : parseInteger(meta.get("r"), 0);
		Element ratingBlock = element.selectFirst("div.comment__rating");
		int userVote = ratingBlock != null ? normalizeVote(parseInteger(ratingBlock.attr("data-vote"), 0)) : 0;
		if (element.selectFirst(".comment__rating-up_active") != null) userVote = 1;
		else if (element.selectFirst(".comment__rating-down_active") != null) userVote = -1;
		post.setVote(Math.max(rating, 0), Math.max(-rating, 0), userVote);
		return post;
	}

	private static int normalizeVote(int vote) {
		return vote > 0 ? 1 : vote < 0 ? -1 : 0;
	}

	private static Post parseXmlComment(String postNumber, String parent, String author, String date,
			String rating, String comment, PikabuChanLocator locator, String threadNumber) {
		if (StringUtils.isEmpty(postNumber) || !NUMBER.matcher(postNumber).matches()) return null;
		if (StringUtils.isEmpty(parent) || !NUMBER.matcher(parent).matches() || "0".equals(parent)
				|| postNumber.equals(parent)) {
			parent = threadNumber;
		}
		Post post = new Post().setThreadNumber(threadNumber).setPostNumber(postNumber)
				.setParentPostNumber(parent).setName(StringUtils.nullIfEmpty(author));
		post.setTimestamp(parseXmlTimestamp(date));
		Element content = Jsoup.parse(StringUtils.emptyIfNull(comment), "https://pikabu.ru/").body();
		ArrayList<Attachment> attachments = parseAttachments(content, locator, postNumber);
		if (!attachments.isEmpty()) post.setAttachments(attachments);
		cleanContent(content, locator, threadNumber);
		prependParentReference(content, locator, threadNumber, postNumber, parent);
		post.setComment(StringUtils.nullIfEmpty(content.html().trim()));
		int value = parseInteger(rating, 0);
		post.setVote(Math.max(value, 0), Math.max(-value, 0));
		return post;
	}

	private static void prependParentReference(Element content, PikabuChanLocator locator, String threadNumber,
			String postNumber, String parent) {
		if (StringUtils.isEmpty(parent) || parent.equals(threadNumber) || parent.equals(postNumber)
				|| !NUMBER.matcher(parent).matches()) return;
		content.prependElement("br");
		content.prependElement("a").attr("href",
				locator.createPostUri(PikabuChanLocator.BOARD_HOT, threadNumber, parent).toString())
				.text(">>" + parent);
	}

	private static ArrayList<Attachment> parseAttachments(Element content, PikabuChanLocator locator,
			String fallbackName) {
		LinkedHashMap<String, Attachment> attachments = new LinkedHashMap<>();
		int index = 0;
		for (Element image : content.select("img")) {
			if (isRestrictedMediaPlaceholder(image)) continue;
			String full = firstNotEmpty(image.attr("data-large-image"), image.attr("data-src"), image.attr("src"));
			Uri fileUri = resolveUri(image, full, locator);
			if (fileUri == null || isInlineData(fileUri)) continue;
			Uri thumbnailUri = resolveUri(image, firstNotEmpty(image.attr("data-src"), image.attr("src")), locator);
			String scramblerOffset = image.attr("data-scrambler-offset");
			fileUri = PikabuImageScrambler.mark(fileUri, scramblerOffset);
			thumbnailUri = PikabuImageScrambler.mark(thumbnailUri, scramblerOffset);
			FileAttachment attachment = new FileAttachment().setFileUri(locator, fileUri);
			if (thumbnailUri != null && !isInlineData(thumbnailUri)) attachment.setThumbnailUri(locator, thumbnailUri);
			attachment.setOriginalName(makeFileName(fileUri, fallbackName, ++index, "jpg"));
			attachment.setWidth(parseInteger(firstNotEmpty(image.attr("width"), image.attr("data-width")), 0));
			attachment.setHeight(parseInteger(firstNotEmpty(image.attr("height"), image.attr("data-height")), 0));
			attachments.putIfAbsent(fileUri.toString(), attachment);
		}
		for (Element video : content.select("video, div.vue-video-player[data-source]")) {
			if (isRestrictedMediaPlaceholder(video)) continue;
			String source = firstNotEmpty(video.attr("data-source"), video.attr("src"));
			if (StringUtils.isEmpty(source)) {
				Element sourceElement = video.selectFirst("source[src]");
				if (sourceElement != null) source = sourceElement.attr("src");
			}
			if ("gifx".equals(video.attr("data-type")) && source != null
					&& source.toLowerCase(Locale.US).endsWith(".gif")) {
				source = source.substring(0, source.length() - 4) + ".mp4";
			} else if (!StringUtils.isEmpty(source) && !FILE_EXTENSION.matcher(source).find()) {
				source += ".mp4";
			}
			Uri fileUri = resolveUri(video, source, locator);
			if (fileUri == null) continue;
			FileAttachment attachment = new FileAttachment().setFileUri(locator, fileUri)
					.setOriginalName(makeFileName(fileUri, fallbackName, ++index, "mp4"))
					.setSize(parseInteger(video.attr("data-mp4-size"), 0))
					.setWidth(parseInteger(video.attr("data-width"), 0))
					.setHeight(parseInteger(video.attr("data-height"), 0));
			Uri thumbnailUri = resolveUri(video,
					firstNotEmpty(video.attr("data-poster"), video.attr("poster"), video.attr("data-thumbnail-url")),
					locator);
			if (thumbnailUri != null) attachment.setThumbnailUri(locator, thumbnailUri);
			attachments.putIfAbsent(fileUri.toString(), attachment);
		}
		return new ArrayList<>(attachments.values());
	}

	private static boolean isRestrictedMediaPlaceholder(Element element) {
		for (Element current = element; current != null; current = current.parent()) {
			if (current.hasClass("story-block_type_stub") || current.hasClass("story__nsfw-stub")
					|| current.hasClass("story__adult-overlay")) return true;
		}
		return false;
	}

	private static void cleanContent(Element content, PikabuChanLocator locator, String threadNumber) {
		for (Element link : content.select("a[href]")) normalizeLink(link, locator, threadNumber);
		content.select("script, style, svg, button, source, picture, video, audio, img, figure, "
				+ ".vue-video-player, "
				+ ".story__more, .story-block_type_image, .story-block_type_video, .story-block_type_gif").remove();
		for (Element link : content.select("a[href]")) {
			if (link.text().trim().isEmpty() && link.children().isEmpty()) link.remove();
		}
	}

	private static void normalizeLink(Element link, PikabuChanLocator locator, String threadNumber) {
		String href = link.attr("href").trim();
		if (href.isEmpty()) return;
		Uri uri = Uri.parse(href.startsWith("//") ? "https:" + href : href);
		String external = uri.isHierarchical() ? uri.getQueryParameter("u") : null;
		if (!StringUtils.isEmpty(external) && uri.getPath() != null && uri.getPath().startsWith("/story/")) {
			link.attr("href", external);
			return;
		}
		String absolute = link.absUrl("href");
		if (!StringUtils.isEmpty(absolute)) uri = Uri.parse(absolute);
		String linkedThread = extractThreadNumber(uri);
		String linkedPost = uri.isHierarchical() ? uri.getQueryParameter("cid") : null;
		if (StringUtils.isEmpty(linkedPost)) {
			String fragment = uri.getFragment();
			if (fragment != null && fragment.startsWith("comment_")
					&& NUMBER.matcher(fragment.substring("comment_".length())).matches()) {
				linkedPost = fragment.substring("comment_".length());
			}
		}
		if (!StringUtils.isEmpty(linkedPost)) {
			if (StringUtils.isEmpty(linkedThread)) linkedThread = threadNumber;
			link.attr("href", locator.createPostUri(PikabuChanLocator.BOARD_HOT, linkedThread, linkedPost).toString());
		} else if (!StringUtils.isEmpty(absolute)) {
			link.attr("href", absolute);
		}
	}

	private static String extractThreadNumber(Uri uri) {
		Matcher matcher = STORY_PATH.matcher(StringUtils.emptyIfNull(uri.getPath()));
		return matcher.matches() ? matcher.group(1) : null;
	}

	private static Uri resolveUri(Element element, String value, PikabuChanLocator locator) {
		if (StringUtils.isEmpty(value)) return null;
		value = value.trim();
		if (value.startsWith("//")) value = "https:" + value;
		Uri uri = Uri.parse(value);
		if (StringUtils.isEmpty(uri.getScheme())) {
			String absolute = element.absUrl(attributeForValue(element, value));
			if (!StringUtils.isEmpty(absolute)) {
				uri = Uri.parse(absolute);
			} else {
				try {
					String resolved = new java.net.URI(element.baseUri()).resolve(value).toString();
					uri = Uri.parse(resolved);
				} catch (IllegalArgumentException | java.net.URISyntaxException e) {
					return null;
				}
			}
		}
		if ("http".equalsIgnoreCase(uri.getScheme())) uri = uri.buildUpon().scheme("https").build();
		return uri;
	}

	private static Uri resolvePageUri(Element element, String value) {
		if (StringUtils.isEmpty(value)) return null;
		value = value.trim();
		if (value.startsWith("//")) value = "https:" + value;
		String absolute = element.absUrl("href");
		Uri uri = Uri.parse(!StringUtils.isEmpty(absolute) ? absolute : value);
		if (StringUtils.isEmpty(uri.getScheme())) {
			try {
				uri = Uri.parse(new java.net.URI(element.baseUri()).resolve(value).toString());
			} catch (IllegalArgumentException | java.net.URISyntaxException e) {
				return null;
			}
		}
		if ("http".equalsIgnoreCase(uri.getScheme())) uri = uri.buildUpon().scheme("https").build();
		return uri;
	}

	private static boolean isPikabuPageUri(Uri uri) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
		String host = uri.getHost();
		return "pikabu.ru".equalsIgnoreCase(host) || "www.pikabu.ru".equalsIgnoreCase(host);
	}

	private static boolean isCommunityPageUri(Uri uri) {
		if (!isPikabuPageUri(uri)) return false;
		List<String> segments = uri.getPathSegments();
		return segments.size() >= 2 && "community".equals(segments.get(0));
	}

	private static boolean isTagPageUri(Uri uri) {
		if (!isPikabuPageUri(uri)) return false;
		List<String> segments = uri.getPathSegments();
		return segments.size() >= 2 && "tag".equals(segments.get(0));
	}

	private static String attributeForValue(Element element, String value) {
		for (String attribute : new String[] {"data-large-image", "data-src", "src", "data-source",
				"data-poster", "poster", "data-thumbnail-url"}) {
			if (value.equals(element.attr(attribute))) return attribute;
		}
		return "src";
	}

	private static boolean isInlineData(Uri uri) {
		return "data".equalsIgnoreCase(uri.getScheme());
	}

	private static String makeFileName(Uri uri, String fallbackName, int index, String fallbackExtension) {
		String name = uri.getLastPathSegment();
		if (!StringUtils.isEmpty(name) && name.contains(".")) return name;
		return fallbackName + '-' + index + '.' + fallbackExtension;
	}

	private static long parseTimestamp(String value) {
		if (StringUtils.isEmpty(value)) return 0L;
		try {
			return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
		} catch (DateTimeException e) {
			return 0L;
		}
	}

	private static long parseXmlTimestamp(String value) {
		if (StringUtils.isEmpty(value)) return 0L;
		try {
			return LocalDateTime.parse(value.trim(), XML_TIMESTAMP).atZone(PIKABU_TIME_ZONE)
					.toInstant().toEpochMilli();
		} catch (DateTimeException e) {
			return 0L;
		}
	}

	private static int parseInteger(String value, int fallback) {
		if (StringUtils.isEmpty(value)) return fallback;
		String normalized = value.trim().replace('\u2212', '-').replace('\u2013', '-')
				.replace("\u00a0", "").replace(" ", "");
		Matcher matcher = Pattern.compile("-?\\d+").matcher(normalized);
		if (!matcher.find()) return fallback;
		try {
			return Integer.parseInt(matcher.group());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static Map<String, String> parseMeta(String value) {
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		Matcher matcher = META_VALUE.matcher(StringUtils.emptyIfNull(value));
		while (matcher.find()) result.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
		return result;
	}

	private static String firstNotEmpty(String... values) {
		for (String value : values) if (!StringUtils.isEmpty(value)) return value;
		return null;
	}

	public static final class ParsedThreads {
		public final ArrayList<Posts> threads;
		public final int pagesCount;
		public final boolean validPage;
		public final String title;
		public final String description;

		private ParsedThreads(ArrayList<Posts> threads, int pagesCount, boolean validPage, String title,
				String description) {
			this.threads = threads;
			this.pagesCount = pagesCount;
			this.validPage = validPage;
			this.title = title;
			this.description = description;
		}
	}

	public static final class ParsedPosts {
		public final Posts posts;
		public final boolean validPage;
		public final boolean fullThread;
		public final int expectedComments;
		public final ArrayList<String> commentIds;

		private ParsedPosts(Posts posts, boolean validPage, boolean fullThread, int expectedComments,
				ArrayList<String> commentIds) {
			this.posts = posts;
			this.validPage = validPage;
			this.fullThread = fullThread;
			this.expectedComments = expectedComments;
			this.commentIds = commentIds;
		}
	}
}
