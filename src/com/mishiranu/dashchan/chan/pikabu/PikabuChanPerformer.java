package com.mishiranu.dashchan.chan.pikabu;

import android.webkit.CookieManager;
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
import com.mishiranu.dashchan.content.net.UserAgentProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PikabuChanPerformer extends ChanPerformer {
	private static final int COMMENTS_BATCH_SIZE = 300;
	private static final String PIKABU_REFERER = "https://pikabu.ru/";
	private static final String HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9," +
			"image/avif,image/webp,image/apng,*/*;q=0.8";
	private static final HttpRequest.RedirectHandler PIKABU_REDIRECT_HANDLER = response ->
			isPikabuDomain(response.getRedirectedUri())
					? HttpRequest.RedirectHandler.BROWSER.onRedirect(response)
					: HttpRequest.RedirectHandler.Action.CANCEL;

	private static boolean isPikabuDomain(android.net.Uri uri) {
		if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
		String host = uri.getHost();
		return host != null && ("pikabu.ru".equalsIgnoreCase(host)
				|| host.toLowerCase(java.util.Locale.US).endsWith(".pikabu.ru"));
	}

	private HttpRequest prepareRequest(android.net.Uri uri, HttpRequest.Preset preset) {
		String userAgent = UserAgentProvider.getInstance().getUserAgent();
		HttpRequest request = new HttpRequest(uri, preset)
				.addHeader("User-Agent", userAgent)
				.addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
		PikabuChanConfiguration configuration = PikabuChanConfiguration.get(this);
		String cookies = isPikabuDomain(uri) ? configuration.getAuthorizationCookies() : null;
		if (!StringUtils.isEmpty(cookies)) {
			String webViewCookies = getWebViewCookies(uri);
			if (!StringUtils.isEmpty(webViewCookies)) {
				String mergedCookies = mergeCookies(cookies, webViewCookies);
				if (!cookies.equals(mergedCookies)) {
					configuration.storeAuthorization(mergedCookies, configuration.getAuthorizedUserName());
				}
				cookies = mergedCookies;
			}
		}
		if (isPikabuDomain(uri)) request.setRedirectHandler(PIKABU_REDIRECT_HANDLER);
		return StringUtils.isEmpty(cookies) ? request : request.addCookie(cookies);
	}

	private HttpRequest preparePageRequest(android.net.Uri uri, HttpRequest.Preset preset) {
		return prepareRequest(uri, preset).addHeader("Accept", HTML_ACCEPT);
	}

	private static String getWebViewCookies(android.net.Uri uri) {
		try {
			return CookieManager.getInstance().getCookie(uri.toString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void syncResponseCookies(android.net.Uri uri, HttpResponse response) {
		if (!isPikabuDomain(uri)) return;
		boolean changed = false;
		try {
			CookieManager cookieManager = CookieManager.getInstance();
			for (Map.Entry<String, List<String>> header : response.getHeaderFields().entrySet()) {
				if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
				for (String value : header.getValue()) {
					cookieManager.setCookie(uri.toString(), value);
					changed = true;
				}
			}
			if (changed) cookieManager.flush();
		} catch (RuntimeException e) {
			// The stored HTTP session remains authoritative when WebView cookies are unavailable.
		}
	}

	private HttpResponse perform(android.net.Uri uri, HttpRequest request) throws HttpException {
		HttpResponse response = request.perform();
		PikabuChanConfiguration configuration = PikabuChanConfiguration.get(this);
		String current = configuration.getAuthorizationCookies();
		if (!StringUtils.isEmpty(current)) {
			String updated = mergeCookies(current, response);
			syncResponseCookies(uri, response);
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

	private static String mergeCookies(String current, String updated) {
		LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
		mergeCookies(cookies, current);
		mergeCookies(cookies, updated);
		return buildCookies(cookies).build();
	}

	private static void mergeCookies(LinkedHashMap<String, String> cookies, String source) {
		if (StringUtils.isEmpty(source)) return;
		CookieBuilder parsed = new CookieBuilder().append(source);
		for (String key : parsed.getKeys()) {
			String value = extractCookieValue(source, key);
			if (value != null) cookies.put(key, value);
		}
	}

	private static String mergeCookies(String current, HttpResponse response) {
		LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
		mergeCookies(cookies, current);
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
		android.net.Uri uri = locator.createBoardUri(data.boardName, data.pageNumber);
		HttpResponse response = perform(uri, preparePageRequest(uri, data)
				.setValidator(data.validator));
		String html = response.readString();
		PikabuHtmlParser.ParsedThreads parsed = PikabuHtmlParser.parseThreads(html, locator,
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
		android.net.Uri uri = locator.createSearchUri(query, data.pageNumber);
		HttpResponse response = perform(uri, preparePageRequest(uri, data));
		String html = response.readString();
		PikabuHtmlParser.ParsedThreads parsed = PikabuHtmlParser.parseSearch(html, locator, query);
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
		android.net.Uri uri = locator.createThreadUri(data.boardName, data.threadNumber);
		HttpResponse response = perform(uri, preparePageRequest(uri, data)
				.setValidator(data.validator));
		String html = response.readString();
		PikabuHtmlParser.ParsedPosts parsed = PikabuHtmlParser.parsePosts(html, locator,
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
				android.net.Uri commentsUri = locator.createThreadCommentsUri(data.threadNumber);
				ArrayList<Post> comments = PikabuHtmlParser.parseCommentsXml(perform(commentsUri,
						prepareRequest(commentsUri, data)).readString(), locator,
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

	@Override
	protected ReadContentResult onReadContent(ReadContentData data)
			throws HttpException, InvalidResponseException {
		int scramblerOffset = PikabuImageScrambler.getOffset(data.uri);
		android.net.Uri requestUri = PikabuImageScrambler.getRequestUri(data.uri);
		HttpRequest request = prepareRequest(requestUri, scramblerOffset >= 0 ? data : data.direct);
		if (isPikabuDomain(requestUri)) {
			request.addHeader("Referer", PIKABU_REFERER);
			HttpResponse response = perform(requestUri, request);
			if (scramblerOffset >= 0) {
				byte[] source;
				try {
					source = response.readBytes();
				} finally {
					response.cleanupAndDisconnect();
				}
				byte[] decoded = PikabuImageScrambler.decode(source, scramblerOffset);
				if (decoded == null) {
					throw new InvalidResponseException();
				}
				return new ReadContentResult(new HttpResponse(decoded));
			}
			return new ReadContentResult(response);
		}
		return new ReadContentResult(request.perform());
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
			android.net.Uri commentsActionsUri = locator.createCommentsActionsUri();
			String json = perform(commentsActionsUri, prepareRequest(commentsActionsUri, preset)
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
	public CheckAuthorizationResult onCheckAuthorization(CheckAuthorizationData data)
			throws HttpException, InvalidResponseException {
		if (data.type != CheckAuthorizationData.TYPE_USER_AUTHORIZATION) {
			throw new InvalidResponseException();
		}
		PikabuChanConfiguration configuration = PikabuChanConfiguration.get(this);
		android.net.Uri uri = android.net.Uri.parse(PIKABU_REFERER);
		HttpResponse response = perform(uri, preparePageRequest(uri, data));
		String html = response.readString();
		PikabuHtmlParser.SessionData sessionData = PikabuHtmlParser.parseSessionData(html);
		if (sessionData == null) throw new InvalidResponseException();
		if (sessionData.authorized) {
			configuration.storeAuthorization(configuration.getAuthorizationCookies(), sessionData.userName);
		} else {
			configuration.clearAuthorization();
		}
		return new CheckAuthorizationResult(sessionData.authorized);
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
			android.net.Uri communitiesUri = locator.createCommunitiesUri();
			communitiesHtml = perform(communitiesUri, preparePageRequest(communitiesUri, data)).readString();
			ArrayList<Board> communities = PikabuHtmlParser.parseCommunities(communitiesHtml);
			if (!communities.isEmpty()) categories.add(new BoardCategory("Сообщества", communities));
		} catch (HttpException e) {
			// Keep the built-in feeds and filters available when the directory is temporarily unavailable.
		}
		try {
			android.net.Uri tagsUri = locator.createTagsUri();
			String tagsHtml = perform(tagsUri, preparePageRequest(tagsUri, data)).readString();
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
