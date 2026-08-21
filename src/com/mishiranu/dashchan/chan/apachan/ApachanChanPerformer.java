package com.mishiranu.dashchan.chan.apachan;

import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.util.StringUtils;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Locale;

public class ApachanChanPerformer extends ChanPerformer {
	private static final String COOKIE_POST_OWNER = "post_owner";

	public static final class EditPostResult {
		public final String subject;
		public final String comment;
		public final boolean originalPost;
		public final boolean hasAttachment;
		public final boolean showOriginalPoster;
		public final int commentLimit;

		private EditPostResult(ApachanHtmlParser.EditForm form) {
			subject = form.subject;
			comment = form.comment;
			originalPost = form.originalPost;
			hasAttachment = form.hasAttachment;
			showOriginalPoster = form.showOriginalPoster;
			commentLimit = form.commentLimit;
		}
	}

	private static String getStoredCookieName(ApachanChanLocator locator) {
		return COOKIE_POST_OWNER + "_" + locator.getPreferredHost().replace('.', '_');
	}

	private static String requirePostOwner(ApachanChanLocator locator, ApachanChanConfiguration configuration)
			throws ApiException {
		String postOwner = configuration.getCookie(getStoredCookieName(locator));
		if (StringUtils.isEmpty(postOwner)) throw new ApiException(ApiException.SEND_ERROR_NO_ACCESS);
		return postOwner;
	}

	public EditPostResult readEditPost(String postNumber, HttpHolder holder) throws HttpException, ApiException,
			InvalidResponseException {
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		String postOwner = requirePostOwner(locator, ApachanChanConfiguration.get(this));
		HttpRequest.Preset preset = () -> holder;
		String html = new HttpRequest(locator.createEditFormUri(postNumber), preset)
				.addCookie(COOKIE_POST_OWNER, postOwner).perform().readString();
		ApachanHtmlParser.EditForm form = ApachanHtmlParser.parseEditForm(html);
		if (form == null) {
			String error = ApachanHtmlParser.parseError(html);
			throw !StringUtils.isEmpty(error) ? new ApiException(error)
					: new ApiException(ApiException.SEND_ERROR_NO_ACCESS);
		}
		return new EditPostResult(form);
	}

	public void sendEditPost(String threadNumber, String postNumber, String subject, String comment,
			boolean originalPost, boolean deleteAttachment, boolean showOriginalPoster, int commentLimit,
			HttpHolder holder) throws HttpException, ApiException, InvalidResponseException {
		ApachanChanLocator locator = ApachanChanLocator.get(this);
		String postOwner = requirePostOwner(locator, ApachanChanConfiguration.get(this));
		HttpRequest.Preset preset = () -> holder;
		MultipartEntity entity = new MultipartEntity();
		entity.add("id", postNumber);
		entity.add("title", subject);
		entity.add("text", comment);
		entity.add("www_file", "");
		if (originalPost) {
			entity.add("comment_limit", Integer.toString(commentLimit));
		} else {
			if (deleteAttachment) entity.add("delete_img", "1");
			if (showOriginalPoster) entity.add("show_op", "1");
		}
		Uri referer = locator.createThreadUri(ApachanChanLocator.DEFAULT_BOARD_NAME, threadNumber);
		HttpResponse response = new HttpRequest(locator.createEditPostingUri(originalPost), preset)
				.setPostMethod(entity).addCookie(COOKIE_POST_OWNER, postOwner)
				.addHeader("Referer", referer.toString()).setRedirectHandler(HttpRequest.RedirectHandler.NONE)
				.setSuccessOnly(false).perform();
		if (response.getResponseCode() >= 300 && response.getResponseCode() < 400) return;
		String error = ApachanHtmlParser.parseError(response.readString());
		if (!StringUtils.isEmpty(error)) throw new ApiException(error);
		throw new InvalidResponseException();
	}

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
		if (data.partialThreadLoading && firstPage.pagesCount > 1) {
			String html = new HttpRequest(locator.createThreadPageUri(data.threadNumber, firstPage.pagesCount), data)
					.perform().readString();
			appendPosts(posts, ApachanHtmlParser.parsePosts(html, locator, data.threadNumber).posts);
		} else {
			for (int page = 2; page <= firstPage.pagesCount; page++) {
				String html = new HttpRequest(locator.createThreadPageUri(data.threadNumber, page), data)
						.perform().readString();
				appendPosts(posts, ApachanHtmlParser.parsePosts(html, locator, data.threadNumber).posts);
			}
		}
		if (posts.isEmpty()) throw new InvalidResponseException();
		return new ReadPostsResult(new Posts(posts.values())).setValidator(response.getValidator())
				.setFullThread(!data.partialThreadLoading || firstPage.pagesCount <= 1);
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
				&& StringUtils.isEmpty(data.userIcon) && StringUtils.isEmpty(data.comment)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		}

		ApachanChanLocator locator = ApachanChanLocator.get(this);
		ApachanChanConfiguration configuration = ApachanChanConfiguration.get(this);
		String storedCookieName = getStoredCookieName(locator);
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
		entity.add("userImage", StringUtils.emptyIfNull(data.userIcon));
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
