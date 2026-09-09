package com.mishiranu.dashchan.chan.d3ru;

import android.net.Uri;
import chan.content.InvalidResponseException;
import chan.content.ChanPerformer;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class D3ChanPerformer extends ChanPerformer {
	private static final int PAGE_SIZE = 16;

	private static HttpRequest createRequest(Uri uri, HttpRequest.Preset preset) {
		return new HttpRequest(uri, preset).addHeader("Accept", "application/json");
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		D3ChanLocator locator = D3ChanLocator.get(this);
		D3ChanConfiguration configuration = D3ChanConfiguration.get(this);
		try {
			JSONObject root = new JSONObject(createRequest(locator.createBoardsApiUri(), data)
					.perform().readString());
			JSONArray array = root.optJSONArray("domains");
			if (array == null) throw new InvalidResponseException();
			ArrayList<Board> boards = new ArrayList<>();
			HashSet<String> names = new HashSet<>();
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object == null || !object.optBoolean("is_readable_for_everyone", true)) continue;
				String name = CommonUtils.optJsonString(object, "prefix");
				if (StringUtils.isEmpty(name)) continue;
				name = name.trim().toLowerCase(Locale.US);
				if (!D3ChanLocator.isBoardName(name) || D3ChanLocator.BOARD_ALL.equals(name)
						|| !names.add(name)) continue;
				String title = CommonUtils.optJsonString(object, "name");
				if (StringUtils.isEmpty(title)) title = name;
				String description = CommonUtils.optJsonString(object, "title");
				if (StringUtils.isEmpty(description)) description = CommonUtils.optJsonString(object, "description");
				boards.add(new Board(name, title, description));
				configuration.storeBoardTitle(name, title);
				configuration.storeBoardDescription(name, description);
				configuration.storePagesCount(name, 2);
			}
			Collections.sort(boards);
			ArrayList<BoardCategory> categories = new ArrayList<>();
			categories.add(new BoardCategory("d3.ru", new Board[] {new Board(D3ChanLocator.BOARD_ALL,
					"Все публикации", "Общая публичная лента d3.ru")}));
			if (!boards.isEmpty()) categories.add(new BoardCategory("Сообщества", boards));
			return new ReadBoardsResult(categories);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		if (data.isCatalog() || !D3ChanLocator.isBoardName(data.boardName)) {
			throw new InvalidResponseException();
		}
		D3ChanLocator locator = D3ChanLocator.get(this);
		D3ChanConfiguration configuration = D3ChanConfiguration.get(this);
		try {
			HttpResponse response = createRequest(locator.createThreadsApiUri(data.boardName, data.pageNumber), data)
					.setValidator(data.validator).perform();
			JSONObject root = new JSONObject(response.readString());
			JSONArray array = root.optJSONArray("posts");
			if (array == null) throw new InvalidResponseException();
			ArrayList<Posts> threads = new ArrayList<>();
			for (int i = 0; i < array.length(); i++) {
				Post post = D3ModelMapper.createThreadPost(array.optJSONObject(i), locator);
				if (post == null) continue;
				JSONObject object = array.optJSONObject(i);
				Posts posts = new Posts(post).addPostsCount(Math.max(1, object.optInt("comments_count") + 1));
				int files = post.getAttachmentsCount();
				if (files > 0) posts.addFilesCount(files).addPostsWithFilesCount(1);
				threads.add(posts);
			}
			configuration.storePagesCount(data.boardName,
					data.pageNumber + (array.length() >= PAGE_SIZE ? 2 : 1));
			return new ReadThreadsResult(threads).setValidator(response.getValidator());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		if (!D3ChanLocator.isBoardName(data.boardName) || StringUtils.isEmpty(data.threadNumber)
				|| !data.threadNumber.matches("\\d+")) throw new InvalidResponseException();
		D3ChanLocator locator = D3ChanLocator.get(this);
		try {
			JSONObject postRoot = new JSONObject(createRequest(locator.createPostApiUri(data.threadNumber), data)
					.perform().readString());
			JSONObject postObject = postRoot.optJSONObject("post");
			if (postObject == null && postRoot.optLong("id") > 0) postObject = postRoot;
			Post originalPost = D3ModelMapper.createThreadPost(postObject, locator);
			if (originalPost == null || !data.threadNumber.equals(originalPost.getPostNumber())) {
				throw new InvalidResponseException();
			}
			JSONObject commentsRoot = new JSONObject(createRequest(locator.createCommentsApiUri(data.threadNumber), data)
					.perform().readString());
			JSONArray comments = commentsRoot.optJSONArray("comments");
			if (comments == null) throw new InvalidResponseException();
			ArrayList<Post> posts = new ArrayList<>(comments.length() + 1);
			posts.add(originalPost);
			HashSet<String> numbers = new HashSet<>();
			numbers.add(data.threadNumber);
			for (int i = 0; i < comments.length(); i++) {
				Post comment = D3ModelMapper.createComment(comments.optJSONObject(i), locator, data.threadNumber);
				if (comment != null && numbers.add(comment.getPostNumber())) posts.add(comment);
			}
			return new ReadPostsResult(new Posts(posts)).setFullThread(true);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}
}
