package com.mishiranu.dashchan.chan.zchan;

import chan.content.ApiException;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public class ZchanChanPerformer extends ChanPerformer {
	private static final int HTTP_POST_CREATED_LEGACY = 402;

	private ZchanIdentity identity;

	private HttpRequest createRequest(android.net.Uri uri, HttpRequest.Preset preset) {
		return new HttpRequest(uri, preset).addHeader("Accept", "application/json");
	}

	private ZchanIdentity getIdentity() {
		if (identity == null) {
			identity = new ZchanIdentity(ZchanChanConfiguration.get(this), ZchanChanLocator.get(this));
		}
		return identity;
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		ZchanChanLocator locator = ZchanChanLocator.get(this);
		ZchanChanConfiguration configuration = ZchanChanConfiguration.get(this);
		HttpResponse response = createRequest(locator.createBoardsApiUri(), data).perform();
		ArrayList<Board> boards = new ArrayList<>();
		boolean hasData = false;
		try (InputStream input = response.open(); JsonSerial.Reader reader = JsonSerial.reader(input)) {
			reader.startObject();
			while (!reader.endStruct()) {
				if ("data".equals(reader.nextName())) {
					hasData = true;
					reader.startArray();
					while (!reader.endStruct()) {
						String name = null;
						String title = null;
						reader.startObject();
						while (!reader.endStruct()) {
							switch (reader.nextName()) {
								case "name":
									name = reader.nextString();
									break;
								case "title":
									title = reader.nextString();
									break;
								default:
									reader.skip();
									break;
							}
						}
						if (!StringUtils.isEmpty(name)) {
							boards.add(new Board(name, StringUtils.isEmpty(title) ? name
									: StringUtils.clearHtml(title)));
							configuration.storePagesCount(name, 1);
						}
					}
				} else {
					reader.skip();
				}
			}
			if (!hasData) throw new InvalidResponseException();
			Collections.sort(boards);
			return new ReadBoardsResult(new BoardCategory("Zchan", boards));
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		if (!data.isCatalog() && data.pageNumber > 0) {
			return new ReadThreadsResult(Collections.emptyList());
		}
		ZchanChanLocator locator = ZchanChanLocator.get(this);
		HttpResponse response = createRequest(locator.createThreadsApiUri(data.boardName), data)
				.setValidator(data.validator).perform();
		ArrayList<Posts> threads = new ArrayList<>();
		boolean hasData = false;
		try (InputStream input = response.open(); JsonSerial.Reader reader = JsonSerial.reader(input)) {
			reader.startObject();
			while (!reader.endStruct()) {
				if ("data".equals(reader.nextName())) {
					hasData = true;
					reader.startArray();
					while (!reader.endStruct()) {
						threads.add(ZchanModelMapper.createCatalogThread(reader, locator, data.boardName));
					}
				} else {
					reader.skip();
				}
			}
			if (!hasData) throw new InvalidResponseException();
			ZchanChanConfiguration.get(this).storePagesCount(data.boardName, 1);
			return new ReadThreadsResult(threads).setValidator(response.getValidator());
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		ZchanChanLocator locator = ZchanChanLocator.get(this);
		boolean partial = data.partialThreadLoading && data.lastPostNumber != null;
		HttpResponse response = createRequest(locator.createThreadApiUri(data.boardName, data.threadNumber,
				partial ? data.lastPostNumber : null), data).setValidator(data.validator).perform();
		ArrayList<Post> posts = new ArrayList<>();
		boolean hasData = false;
		try (InputStream input = response.open(); JsonSerial.Reader reader = JsonSerial.reader(input)) {
			reader.startObject();
			while (!reader.endStruct()) {
				if ("data".equals(reader.nextName())) {
					hasData = true;
					reader.startArray();
					while (!reader.endStruct()) {
						Post post = ZchanModelMapper.createPost(reader, locator, data.boardName);
						if (!partial || !data.lastPostNumber.equals(post.getPostNumber())) posts.add(post);
					}
				} else {
					reader.skip();
				}
			}
			if (!hasData) throw new InvalidResponseException();
			ReadPostsResult result = new ReadPostsResult(new Posts(posts)).setValidator(response.getValidator());
			return partial ? result : result.setFullThread(true);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		boolean newThread = data.threadNumber == null;
		if (StringUtils.isEmpty(data.comment) && (data.attachments == null || data.attachments.length == 0)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		}

		ZchanChanLocator locator = ZchanChanLocator.get(this);
		ZchanIdentity identity = getIdentity();
		String zid = identity.obtainZid(data);
		MultipartEntity entity = new MultipartEntity();
		entity.add("body", StringUtils.emptyIfNull(data.comment));
		entity.add("platform", "zchan");
		entity.add("lang", identity.obtainLanguage());
		entity.add("board", data.boardName);
		entity.add("notify", "true");
		if (newThread) {
			entity.add("title", StringUtils.emptyIfNull(data.subject));
		} else {
			entity.add("thread_id", data.threadNumber);
			entity.add("name", StringUtils.emptyIfNull(data.name));
			entity.add("is_op", Boolean.toString(data.optionOriginalPoster));
		}
		identity.addClientFields(entity, zid);
		if (data.attachments != null) {
			String field = newThread ? "files" : "files[]";
			for (SendPostData.Attachment attachment : data.attachments) {
				attachment.addToEntity(entity, field);
			}
		}

		HttpResponse response = createRequest(locator.createPostingApiUri(data.boardName, data.threadNumber), data)
				.setPostMethod(entity).setSuccessOnly(false).perform();
		String responseText = response.readString();
		int responseCode = response.getResponseCode();
		if ((responseCode >= 200 && responseCode < 300) || responseCode == HTTP_POST_CREATED_LEGACY) {
			try {
				JSONObject object = new JSONObject(responseText);
				JSONObject responseData = object.optJSONObject("data");
				Object id = responseData != null ? responseData.opt("id") : null;
				String postNumber = id != null && id != JSONObject.NULL ? String.valueOf(id) : null;
				if (StringUtils.isEmpty(postNumber)) throw new InvalidResponseException();
				return newThread ? new SendPostResult(postNumber, null)
						: new SendPostResult(data.threadNumber, postNumber);
			} catch (JSONException e) {
				throw new InvalidResponseException(e);
			}
		}

		if (responseCode == 401 || responseCode == 403) {
			identity.invalidateZid();
			throw new ApiException("Zchan rejected the local posting ID. Retry to request a new ID; "
					+ "if the error persists, posting from Slooop requires approval.");
		}
		if (responseCode == 404) {
			throw new ApiException(newThread ? ApiException.SEND_ERROR_NO_BOARD : ApiException.SEND_ERROR_NO_THREAD);
		}
		if (responseCode == 413) throw new ApiException(ApiException.SEND_ERROR_FILE_TOO_BIG);
		if (responseCode == 415) throw new ApiException(ApiException.SEND_ERROR_FILE_NOT_SUPPORTED);
		if (responseCode == 429) throw new ApiException(ApiException.SEND_ERROR_TOO_FAST);
		String message = ZchanIdentity.readError(responseText, "Zchan rejected the post (HTTP "
				+ responseCode + ")");
		String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains("too fast") || lower.contains("flood") || lower.contains("част")) {
			throw new ApiException(ApiException.SEND_ERROR_TOO_FAST);
		}
		throw new ApiException(message);
	}
}
