package com.mishiranu.dashchan.chan.apachan;

import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.util.StringUtils;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Locale;

public class ApachanChanPerformer extends ChanPerformer {
	private static final String COOKIE_POST_OWNER = "post_owner";

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		String html = new HttpRequest(locator.buildPath(), data).perform().readString();
		BoardCategory category = ApachanHtmlParser.parseBoards(html);
		if (category == null) throw new InvalidResponseException();
		return new ReadBoardsResult(category);
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		if (data.isCatalog()) throw new InvalidResponseException();
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		HttpResponse response = new HttpRequest(locator.createBoardUri(data.boardName, data.pageNumber), data)
				.setValidator(data.validator).perform();
		ApachanHtmlParser.ParsedThreads parsed = ApachanHtmlParser.parseThreads(response.readString(), locator);
		if (!parsed.validBoardPage) throw new InvalidResponseException();
		ApachanChanConfiguration.get(this).storePagesCount(data.boardName, parsed.pagesCount);
		return new ReadThreadsResult(parsed.threads).setValidator(response.getValidator());
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		HttpResponse response = new HttpRequest(locator.createThreadUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator).perform();
		ApachanHtmlParser.ParsedPosts firstPage = ApachanHtmlParser.parsePosts(response.readString(), locator,
				data.threadNumber);
		LinkedHashMap<String, Post> posts = new LinkedHashMap<>();
		appendPosts(posts, firstPage.posts);
		for (int page = 2; page <= firstPage.pagesCount; page++) {
			String html = new HttpRequest(locator.createThreadPageUri(data.threadNumber, page), data).perform().readString();
			appendPosts(posts, ApachanHtmlParser.parsePosts(html, locator, data.threadNumber).posts);
		}
		if (posts.isEmpty()) throw new InvalidResponseException();
		return new ReadPostsResult(new Posts(posts.values())).setValidator(response.getValidator()).setFullThread(true);
	}

	@Override
	public ReadPostsCountResult onReadPostsCount(ReadPostsCountData data) throws HttpException,
			InvalidResponseException {
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		HttpResponse response = new HttpRequest(locator.createThreadUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator).perform();
		int postsCount = ApachanHtmlParser.parsePosts(response.readString(), locator, data.threadNumber).postsCount;
		if (postsCount < 1) throw new InvalidResponseException();
		return new ReadPostsCountResult(postsCount).setValidator(response.getValidator());
	}

	private static void appendPosts(LinkedHashMap<String, Post> target, Iterable<Post> source) {
		for (Post post : source) target.put(post.getPostNumber(), post);
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		if (data.threadNumber != null && (data.attachments == null || data.attachments.length == 0)
				&& StringUtils.isEmpty(data.comment)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		}

		ApachanChanLocator locator = ApachanChanLocator.get(this);
		ApachanChanConfiguration configuration = ApachanChanConfiguration.get(this);
		String storedCookieName = COOKIE_POST_OWNER + "_" + locator.getPreferredHost().replace('.', '_');
		boolean newThread = data.threadNumber == null;
		String sectionId = null;
		String pageCookie = null;
		Uri referer = newThread ? locator.createBoardUri(data.boardName, 0)
				: locator.createThreadUri(data.boardName, data.threadNumber);
		if (newThread) {
			HttpResponse boardResponse = new HttpRequest(referer, data).setSuccessOnly(false).perform();
			if (boardResponse.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
				throw new ApiException(ApiException.SEND_ERROR_NO_BOARD);
			}
			boardResponse.checkResponseCode();
			sectionId = ApachanHtmlParser.parseSectionId(boardResponse.readString());
			pageCookie = boardResponse.getCookieValue(COOKIE_POST_OWNER);
			if (StringUtils.isEmpty(sectionId)) throw new InvalidResponseException();
		}

		String postOwner = configuration.getCookie(storedCookieName);
		if (StringUtils.isEmpty(postOwner)) postOwner = pageCookie;
		if (StringUtils.isEmpty(postOwner)) {
			Uri cookieUri = newThread ? locator.createCookieBootstrapUri() : referer;
			postOwner = new HttpRequest(cookieUri, data).perform().getCookieValue(COOKIE_POST_OWNER);
		}
		if (StringUtils.isEmpty(postOwner)) throw new InvalidResponseException();
		configuration.storeCookie(storedCookieName, postOwner, "Post owner (" + locator.getPreferredHost() + ")");

		MultipartEntity entity = new MultipartEntity();
		entity.add(newThread ? "sec" : "id", newThread ? sectionId : data.threadNumber);
		entity.add("title", data.subject);
		entity.add("email", "");
		entity.add("text", data.comment);
		entity.add("www_file", "");
		entity.add("userImage", "");
		if (data.attachments != null && data.attachments.length > 0) {
			data.attachments[0].addToEntity(entity, "img");
		}

		HttpResponse response = new HttpRequest(locator.createPostingUri(newThread), data)
				.setPostMethod(entity).addCookie(COOKIE_POST_OWNER, postOwner)
				.addHeader("Referer", referer.toString()).setRedirectHandler(HttpRequest.RedirectHandler.NONE)
				.setSuccessOnly(false).perform();
		if (response.getResponseCode() >= 300 && response.getResponseCode() < 400) {
			Uri redirect = response.getRedirectedUri();
			String threadNumber = locator.getThreadNumber(redirect);
			String postNumber = locator.getPostNumber(redirect);
			if (StringUtils.isEmpty(threadNumber)) throw new InvalidResponseException();
			return new SendPostResult(threadNumber, newThread ? null : postNumber);
		}

		String error = ApachanHtmlParser.parseError(response.readString());
		if (StringUtils.isEmpty(error)) throw new InvalidResponseException();
		throw mapPostingError(error);
	}

	private static ApiException mapPostingError(String message) {
		String normalized = message.toLowerCase(Locale.ROOT);
		if (normalized.contains("отсутствует текст") || normalized.contains("пустое сообщение")) {
			return new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		}
		if (normalized.contains("не удалось обработать изображение")) {
			return new ApiException(ApiException.SEND_ERROR_EMPTY_FILE);
		}
		if (normalized.contains("404") || normalized.contains("тред не найден")) {
			return new ApiException(ApiException.SEND_ERROR_NO_THREAD);
		}
		if (normalized.contains("превышение лимита") || normalized.contains("повторяющийся текст")
				|| normalized.contains("слишком часто")) {
			return new ApiException(ApiException.SEND_ERROR_TOO_FAST);
		}
		if (normalized.contains("идентичное изображение")) {
			return new ApiException(ApiException.SEND_ERROR_FILE_EXISTS);
		}
		if (normalized.contains("заблокировал") || normalized.contains("нет доступа")) {
			return new ApiException(ApiException.SEND_ERROR_NO_ACCESS);
		}
		return new ApiException(message);
	}
}
