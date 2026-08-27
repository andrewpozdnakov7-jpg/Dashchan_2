package com.mishiranu.dashchan.chan.pikabu;

import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.UrlEncodedEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public class PikabuChanPerformer extends ChanPerformer {
	private static final int COMMENTS_BATCH_SIZE = 300;
	private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) "
			+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36";

	private static HttpRequest prepareRequest(android.net.Uri uri, HttpRequest.Preset preset) {
		return new HttpRequest(uri, preset)
				.addHeader("User-Agent", MOBILE_USER_AGENT)
				.addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		if (!PikabuHtmlParser.isBoardName(data.boardName) || data.isCatalog()) {
			throw new InvalidResponseException();
		}
		HttpResponse response = prepareRequest(locator.createBoardUri(data.boardName, data.pageNumber), data)
				.setValidator(data.validator).perform();
		PikabuHtmlParser.ParsedThreads parsed = PikabuHtmlParser.parseThreads(response.readString(), locator,
				data.boardName);
		if (!parsed.validPage) throw new InvalidResponseException();
		if (parsed.pagesCount > 0) {
			ChanConfiguration.get(this).storePagesCount(data.boardName, parsed.pagesCount);
		}
		return new ReadThreadsResult(parsed.threads).setValidator(response.getValidator());
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		PikabuChanLocator locator = PikabuChanLocator.get(this);
		HttpResponse response = prepareRequest(locator.createThreadUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator).perform();
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
				ArrayList<Post> comments = PikabuHtmlParser.parseCommentsXml(prepareRequest(
						locator.createThreadCommentsUri(data.threadNumber), data).perform().readString(), locator,
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

	private static ArrayList<Post> readCommentsByIds(PikabuChanLocator locator, HttpRequest.Preset preset,
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
			String json = prepareRequest(locator.createCommentsActionsUri(), preset)
					.addHeader("Accept", "application/json")
					.addHeader("Referer", locator.createThreadUri(PikabuChanLocator.BOARD_HOT,
							threadNumber).toString())
					.setPostMethod(entity).perform().readString();
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
		return new ReadBoardsResult(new BoardCategory("Pikabu", Arrays.asList(
				new Board(PikabuChanLocator.BOARD_HOT, "Горячее", "Популярные истории"),
				new Board(PikabuChanLocator.BOARD_BEST, "Лучшее", "Лучшие истории"),
				new Board(PikabuChanLocator.BOARD_NEW, "Свежее", "Новые истории"))));
	}
}
