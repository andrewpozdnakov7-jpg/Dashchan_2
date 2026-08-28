package com.mishiranu.dashchan.chan.pikabu;

import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.CookieBuilder;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.UrlEncodedEntity;
import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PikabuChanPerformer extends ChanPerformer {
	private static final int COMMENTS_BATCH_SIZE = 300;
	private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) "
			+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36";

	private HttpRequest prepareRequest(android.net.Uri uri, HttpRequest.Preset preset) {
		HttpRequest request = new HttpRequest(uri, preset)
				.addHeader("User-Agent", MOBILE_USER_AGENT)
				.addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
		String cookies = PikabuChanConfiguration.get(this).getAuthorizationCookies();
		return StringUtils.isEmpty(cookies) ? request : request.addCookie(cookies);
	}

	private HttpResponse perform(HttpRequest request) throws HttpException {
		HttpResponse response = request.perform();
		PikabuChanConfiguration configuration = PikabuChanConfiguration.get(this);
		String current = configuration.getAuthorizationCookies();
		if (!StringUtils.isEmpty(current)) {
			String updated = mergeCookies(current, response);
			if (!current.equals(updated)) {
				configuration.storeAuthorization(updated, configuration.getAuthorizedUserName());
			}
		}
		return response;
	}

	private static CookieBuilder buildCookies(Map<String, String> cookies) {
		CookieBuilder builder = new CookieBuilder();
		for (Map.Entry<String, String> entry : cookies.entrySet()) builder.append(entry.getKey(), entry.getValue());
		return builder;
	}

	private static String mergeCookies(String current, HttpResponse response) {
		LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
		CookieBuilder parsed = new CookieBuilder().append(current);
		for (String key : parsed.getKeys()) {
			String value = extractCookieValue(current, key);
			if (value != null) cookies.put(key, value);
		}
		mergeCookies(cookies, response);
		return buildCookies(cookies).build();
	}

	private static void mergeCookies(LinkedHashMap<String, String> cookies, HttpResponse response) {
		for (Map.Entry<String, List<String>> header : response.getHeaderFields().entrySet()) {
			if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
			for (String value : header.getValue()) {
				int separator = value.indexOf(';');
				String nameValue = (separator >= 0 ? value.substring(0, separator) : value).trim();
				int equals = nameValue.indexOf('=');
				if (equals <= 0) continue;
				String name = nameValue.substring(0, equals).trim();
				String cookieValue = nameValue.substring(equals + 1);
				if (!isCookieName(name)) continue;
				String attributes = separator >= 0 ? value.substring(separator + 1) : "";
				boolean expired = attributes.matches("(?is).*(?:^|;)\\s*max-age\\s*=\\s*0(?:\\s*;|$).*");
				if (cookieValue.isEmpty() || "deleted".equalsIgnoreCase(cookieValue) || expired) cookies.remove(name);
				else cookies.put(name, cookieValue);
			}
		}
	}

	private static String extractCookieValue(String cookies, String key) {
		for (String part : cookies.split("; *")) {
			int equals = part.indexOf('=');
			if (equals > 0 && key.equals(part.substring(0, equals))) return part.substring(equals + 1);
		}
		return null;
	}

	private static boolean isCookieName(String name) {
		if (name.isEmpty()) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c <= 0x20 || c >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) return false;
		}
		return true;
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		if (!PikabuHtmlParser.isBoardName(data.boardName) || data.isCatalog()) {
			throw new InvalidResponseException();
		}
		HttpResponse response = perform(prepareRequest(locator.createBoardUri(data.boardName, data.pageNumber), data)
				.setValidator(data.validator));
		PikabuHtmlParser.ParsedThreads parsed = PikabuHtmlParser.parseThreads(response.readString(), locator,
				data.boardName);
		if (!parsed.validPage) throw new InvalidResponseException();
		ChanConfiguration configuration = ChanConfiguration.get(this);
		String dynamicTitle = PikabuChanLocator.getDynamicBoardTitle(data.boardName);
		if (dynamicTitle != null) {
			configuration.storeBoardTitle(data.boardName,
					!StringUtils.isEmpty(parsed.title) ? parsed.title : dynamicTitle);
			if (!StringUtils.isEmpty(parsed.description)) {
				configuration.storeBoardDescription(data.boardName, parsed.description);
			}
		}
		if (parsed.pagesCount > 0) {
			configuration.storePagesCount(data.boardName, parsed.pagesCount);
		}
		return new ReadThreadsResult(parsed.threads).setValidator(response.getValidator());
	}

	@Override
	public ReadSearchPostsResult onReadSearchPosts(ReadSearchPostsData data)
			throws HttpException, InvalidResponseException {
		String query = data.searchQuery != null ? data.searchQuery.trim() : "";
		if (query.isEmpty()) return new ReadSearchPostsResult();
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		HttpResponse response = perform(prepareRequest(locator.createSearchUri(query, data.pageNumber), data));
		PikabuHtmlParser.ParsedThreads parsed = PikabuHtmlParser.parseSearch(response.readString(), locator, query);
		if (!parsed.validPage) throw new InvalidResponseException();
		ArrayList<Post> posts = new ArrayList<>();
		for (Posts thread : parsed.threads) {
			Post[] threadPosts = thread.getPosts();
			if (threadPosts != null && threadPosts.length > 0) posts.add(threadPosts[0]);
		}
		return new ReadSearchPostsResult(posts);
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		HttpResponse response = perform(prepareRequest(locator.createThreadUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator));
		PikabuHtmlParser.ParsedPosts parsed = PikabuHtmlParser.parsePosts(response.readString(), locator,
				data.threadNumber);
		if (!parsed.validPage || parsed.posts.length() == 0) throw new InvalidResponseException();
		Posts posts = parsed.posts;
		boolean fullThread = parsed.fullThread;
		if (!fullThread && !parsed.commentIds.isEmpty()) {
			try {
				ArrayList<Post> comments = readCommentsByIds(locator, data, data.threadNumber,
						parsed.commentIds);
				if (comments != null) {
					LinkedHashMap<String, Post> allPosts = mergePosts(posts, comments);
					posts = new Posts(allPosts.values());
					fullThread = parsed.expectedComments >= 0
							&& allPosts.size() - 1 >= parsed.expectedComments;
				}
			} catch (HttpException e) {
				// Fall through to the legacy read-only endpoint when a batch is unavailable.
			}
		}
		if (!fullThread) {
			try {
				ArrayList<Post> comments = PikabuHtmlParser.parseCommentsXml(perform(prepareRequest(
						locator.createThreadCommentsUri(data.threadNumber), data)).readString(), locator,
						data.threadNumber);
				if (parsed.expectedComments >= 0 && comments != null
						&& comments.size() >= parsed.expectedComments) {
					LinkedHashMap<String, Post> allPosts = mergePosts(posts, comments);
					posts = new Posts(allPosts.values());
					fullThread = true;
				}
			} catch (HttpException e) {
				// The story page remains usable if the read-only comments endpoint is unavailable.
			}
		}
		return new ReadPostsResult(posts).setValidator(response.getValidator()).setFullThread(fullThread);
	}

	private ArrayList<Post> readCommentsByIds(PikabuChanLocator locator, HttpRequest.Preset preset,
			String threadNumber, List<String> commentIds) throws HttpException {
		ArrayList<Post> comments = new ArrayList<>();
		for (int start = 0; start < commentIds.size(); start += COMMENTS_BATCH_SIZE) {
			int end = Math.min(start + COMMENTS_BATCH_SIZE, commentIds.size());
			StringBuilder ids = new StringBuilder();
			for (int i = start; i < end; i++) {
				if (ids.length() > 0) ids.append(',');
				ids.append(commentIds.get(i));
			}
			UrlEncodedEntity entity = new UrlEncodedEntity("action", "get_comments_by_ids",
					"ids", ids.toString());
			String json = perform(prepareRequest(locator.createCommentsActionsUri(), preset)
					.addHeader("Accept", "application/json")
					.addHeader("Referer", locator.createThreadUri(PikabuChanLocator.BOARD_HOT,
							threadNumber).toString())
					.setPostMethod(entity)).readString();
			ArrayList<Post> batch = PikabuHtmlParser.parseCommentsJson(json, locator, threadNumber);
			if (batch == null) return null;
			comments.addAll(batch);
		}
		return comments;
	}

	private static LinkedHashMap<String, Post> mergePosts(Posts current, List<Post> comments) {
		LinkedHashMap<String, Post> allPosts = new LinkedHashMap<>();
		for (Post post : current.getPosts()) allPosts.put(post.getPostNumber(), post);
		for (Post post : comments) allPosts.putIfAbsent(post.getPostNumber(), post);
		return allPosts;
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) {
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		ArrayList<BoardCategory> categories = new ArrayList<>();
		categories.add(new BoardCategory("Ленты", Arrays.asList(
				new Board(PikabuChanLocator.BOARD_HOT, "Горячее", "Популярные истории"),
				new Board(PikabuChanLocator.BOARD_BEST, "Лучшее", "Лучшие истории"),
				new Board(PikabuChanLocator.BOARD_NEW, "Свежее", "Новые истории"))));
		categories.add(new BoardCategory("Фильтры", Arrays.asList(
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_ORIGINAL),
						"Авторские", "Истории с отметкой «моё»"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_TEXT),
						"Текстовые", "Истории с текстом"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_IMAGES),
						"Изображения", "Истории с изображениями"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_VIDEO),
						"Видео", "Истории с видео"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_REPLIES),
						"Ответы на истории", "Истории-ответы"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_SERIES),
						"Серии", "Истории из серий"),
				new Board(PikabuChanLocator.createBrowseBoardName(PikabuChanLocator.BROWSE_LONG),
						"Длиннопосты", "Длинные истории"))));

		String communitiesHtml = null;
		try {
			communitiesHtml = perform(prepareRequest(locator.createCommunitiesUri(), data)).readString();
			ArrayList<Board> communities = PikabuHtmlParser.parseCommunities(communitiesHtml);
			if (!communities.isEmpty()) categories.add(new BoardCategory("Сообщества", communities));
		} catch (HttpException e) {
			// Keep the built-in feeds and filters available when the directory is temporarily unavailable.
		}
		try {
			String tagsHtml = perform(prepareRequest(locator.createTagsUri(), data)).readString();
			ArrayList<Board> tags = PikabuHtmlParser.parseTags(tagsHtml);
			if (!tags.isEmpty()) categories.add(new BoardCategory("Популярные теги", tags));
		} catch (HttpException e) {
			// Tags are optional navigation data; reading known feeds must continue to work.
		}
		if (communitiesHtml != null) {
			ArrayList<Board> authors = PikabuHtmlParser.parsePopularAuthors(communitiesHtml);
			if (!authors.isEmpty()) categories.add(new BoardCategory("Популярные авторы", authors));
		}
		return new ReadBoardsResult(categories);
	}
}
