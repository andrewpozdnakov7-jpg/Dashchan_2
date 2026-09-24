package com.mishiranu.dashchan.chan.kohlchan;

import android.graphics.Bitmap;
import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanConfiguration;
import chan.content.ChanLocator;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.MultipartEntity;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.ui.ForegroundManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class KohlchanChanPerformer extends ChanPerformer {
	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		try {
			JSONArray threads;
			if (data.isCatalog()) {
				threads = new JSONArray(new HttpRequest(locator.buildPath(data.boardName, "catalog.json"), data)
						.setValidator(data.validator).perform().readString());
			} else {
				JSONObject object = new JSONObject(new HttpRequest(locator.buildPath(data.boardName,
						(data.pageNumber + 1) + ".json"), data)
						.setValidator(data.validator).perform().readString());
				KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
				configuration.updateBoard(data.boardName, object);
				threads = object.getJSONArray("threads");
			}
			return threads.length() > 0
					? new ReadThreadsResult(KohlchanModelMapper.createThreads(threads, locator)) : null;
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		try {
			JSONObject object = new JSONObject(new HttpRequest(locator.buildPath(data.boardName, "res",
					data.threadNumber + ".json"), data).setValidator(data.validator).perform().readString());
			KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
			configuration.updateBoard(data.boardName, object);
			return new ReadPostsResult(KohlchanModelMapper.createPosts(object, locator));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
		try {
			ArrayList<Board> boards = new ArrayList<>();
			HashSet<String> names = new HashSet<>();
			int pageCount = 1;
			for (int page = 1; page <= pageCount; page++) {
				JSONObject response = new JSONObject(new HttpRequest(locator.buildQuery("boards.js",
						"json", "1", "page", Integer.toString(page)), data).perform().readString());
				if (!"ok".equals(response.optString("status"))) throw new JSONException("Invalid boards status");
				JSONObject object = response.getJSONObject("data");
				if (page == 1) {
					pageCount = Math.max(1, object.optInt("pageCount", 1));
					if (pageCount > 100) throw new JSONException("Too many board pages");
				}
				JSONArray array = object.getJSONArray("boards");
				for (int i = 0; i < array.length(); i++) {
					JSONObject board = array.getJSONObject(i);
					String name = CommonUtils.optJsonString(board, "boardUri");
					if (name == null || !name.matches("[A-Za-z0-9_-]+") || !names.add(name)) continue;
					String title = CommonUtils.optJsonString(board, "boardName");
					String description = CommonUtils.optJsonString(board, "boardDescription");
					boards.add(new Board(name, title != null && !title.isEmpty() ? title : name, description));
					configuration.updateBoard(name, board);
				}
			}
			return new ReadBoardsResult(new BoardCategory("Kohlchan", boards));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
		try {
			JSONObject board = new JSONObject(new HttpRequest(locator.buildPath(data.boardName, "1.json"), data)
					.perform().readString());
			configuration.updateBoard(data.boardName, board);
			int mode = board.optInt("captchaMode", -1);
			boolean required;
			if (mode >= 0) {
				required = StringUtils.isEmpty(data.threadNumber) ? mode >= 1 : mode >= 2;
			} else {
				Uri page = StringUtils.isEmpty(data.threadNumber) ? locator.createBoardUri(data.boardName, 0)
						: locator.createThreadUri(data.boardName, data.threadNumber);
				required = new HttpRequest(page, data).perform().readString().contains("id=\"captchaDiv\"");
			}
			if (!required) return new ReadCaptchaResult(CaptchaState.SKIP, null);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		HttpResponse response = new HttpRequest(locator.buildQuery("captcha.js", "d",
				Long.toString(System.currentTimeMillis())), data).perform();
		Bitmap image = response.readBitmap();
		String id = response.getCookieValue("captchaid");
		if (image == null || StringUtils.isEmpty(id)) {
			if (image != null) image.recycle();
			throw new InvalidResponseException();
		}
		CaptchaData captcha = new CaptchaData();
		captcha.put(CaptchaData.CHALLENGE, id);
		return new ReadCaptchaResult(CaptchaState.CAPTCHA, captcha).setImage(image);
	}

	private JSONObject postForm(String endpoint, MultipartEntity entity, HttpRequest.Preset preset,
			Uri referer, String captchaId) throws HttpException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		HttpRequest request = new HttpRequest(locator.buildQuery(endpoint + ".js", "json", "1"), preset)
				.setPostMethod(entity).setRedirectHandler(HttpRequest.RedirectHandler.NONE)
				.addHeader("Referer", referer.toString()).addHeader("Origin", "https://kohlchan.net");
		if (!StringUtils.isEmpty(captchaId)) request.addCookie("captchaid", captchaId);
		String permission = KohlchanAccess.getCookie(get());
		if (permission != null) request.addCookie("bypass", permission);
		HttpResponse response;
		try {
			response = request.perform();
		} catch (HttpException e) {
			KohlchanAccess.trace(endpoint, e.getResponseCode(), "http_error", permission != null);
			throw e;
		}
		try {
			JSONObject object = new JSONObject(response.readString());
			KohlchanAccess.trace(endpoint, response.getResponseCode(), object.optString("status"), permission != null);
			return object;
		} catch (JSONException e) {
			KohlchanAccess.trace(endpoint, response.getResponseCode(), "invalid_json", permission != null);
			throw new InvalidResponseException();
		}
	}

	private void requestAccessAndStop() throws ApiException, HttpException {
		boolean success;
		try {
			success = ForegroundManager.getInstance().requireKohlchanAccess();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HttpException(ErrorItem.Type.UNKNOWN, false, false, e);
		}
		// The user must explicitly send again. Never replay a post after a verification window.
		throw new ApiException(ChanConfiguration.get(this).getResources().getString(success
				? R.string.kohlchan_access_retry : R.string.kohlchan_access_cancelled));
	}

	private static String postingPassword(String password) {
		// Match the website for both creation and deletion, including its eight-character limit.
		password = StringUtils.emptyIfNull(password).trim();
		return password.length() > 8 ? password.substring(0, 8) : password;
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		KohlchanChanConfiguration configuration = ChanConfiguration.get(this);
		boolean newThread = StringUtils.isEmpty(data.threadNumber);
		Uri referer = newThread ? locator.createBoardUri(data.boardName, 0)
				: locator.createThreadUri(data.boardName, data.threadNumber);
		int count = data.attachments != null ? data.attachments.length : 0;
		ChanConfiguration.Posting posting = configuration.obtainPostingConfiguration(data.boardName, newThread);
		if (count > posting.attachmentCount) throw new ApiException(ApiException.SEND_ERROR_FILES_TOO_MANY);
		if (newThread && count == 0 && configuration.requiresThreadFile(data.boardName)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_FILE);
		}
		if (count == 0 && StringUtils.isEmpty(data.comment)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		}
		long maxFileSize = configuration.getMaxFileSize(data.boardName);
		if (maxFileSize > 0 && data.attachments != null) {
			for (SendPostData.Attachment attachment : data.attachments) {
				if (attachment.getSize() > maxFileSize) throw new ApiException(ApiException.SEND_ERROR_FILE_TOO_BIG);
			}
		}
		KohlchanAccess.State access = KohlchanAccess.check(get(), data.holder, KohlchanAccess.getCookie(get()));
		if (!access.valid) {
			KohlchanAccess.storeCookie(get(), null);
			if (access.mode == 2) requestAccessAndStop();
		}

		// The website validates the user's answer first and sends the validated ID as "captcha".
		// Neither the answer nor the resulting token is logged or saved as a persistent cookie.
		String captchaId = data.captchaData != null ? data.captchaData.get(CaptchaData.CHALLENGE) : null;
		if (!StringUtils.isEmpty(captchaId)) {
			String answer = data.captchaData.get(CaptchaData.INPUT);
			if (StringUtils.isEmpty(answer)) throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
			JSONObject solved = postForm("solveCaptcha", new MultipartEntity("captchaId", captchaId,
					"answer", answer.trim()), data, referer, captchaId);
			if (!"ok".equals(solved.optString("status"))) {
				checkResponse(solved, true);
				throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
			}
		}

		MultipartEntity entity = new MultipartEntity("boardUri", data.boardName,
				"threadId", newThread ? null : data.threadNumber,
				"name", posting.allowName ? data.name : null, "subject", data.subject,
				"message", StringUtils.emptyIfNull(data.comment), "password", postingPassword(data.password),
				"captcha", captchaId);
		if (data.optionSage) entity.add("sage", "true");
		if (data.optionSpoiler) entity.add("spoiler", "true");
		if (data.attachments != null) {
			for (SendPostData.Attachment attachment : data.attachments) {
				// Stream through the common attachment pipeline (metadata removal/reencoding/progress).
				// Do not buffer videos in memory or skip the user's selected transformations.
				attachment.addToEntity(entity, "files");
			}
		}
		JSONObject response = postForm(newThread ? "newThread" : "replyThread", entity, data, referer, captchaId);
		checkResponse(response, false);
		String postNumber = response.optString("data");
		if (!postNumber.matches("[1-9][0-9]*")) throw new InvalidResponseException();
		return new SendPostResult(newThread ? postNumber : data.threadNumber, newThread ? null : postNumber);
	}

	@Override
	public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data)
			throws HttpException, ApiException, InvalidResponseException {
		KohlchanChanLocator locator = ChanLocator.get(this);
		if (data.postNumbers == null || data.postNumbers.isEmpty()) throw new InvalidResponseException();
		MultipartEntity entity = new MultipartEntity("action", "delete", "password", postingPassword(data.password));
		HashSet<String> selected = new HashSet<>(data.postNumbers);
		for (String number : selected) {
			if (number == null || !number.matches("[1-9][0-9]*")) throw new InvalidResponseException();
			entity.add(data.boardName + "-" + data.threadNumber
					+ (number.equals(data.threadNumber) ? "" : "-" + number), "true");
		}
		JSONObject response = postForm("contentActions", entity, data,
				locator.createThreadUri(data.boardName, data.threadNumber), null);
		if (!"ok".equals(response.optString("status"))) {
			if ("bypassable".equals(response.optString("status")) || "hashcash".equals(response.optString("status"))) {
				requestAccessAndStop();
			}
			String message = response.optString("data").toLowerCase(Locale.US);
			if (message.contains("password") || message.contains("invalid account")) {
				throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
			}
			throw new ApiException(ApiException.DELETE_ERROR_NO_ACCESS);
		}
		JSONObject result = response.optJSONObject("data");
		if (result == null || !result.has("removedThreads") || !result.has("removedPosts")) {
			throw new InvalidResponseException();
		}
		int threads = result.optInt("removedThreads");
		int posts = result.optInt("removedPosts");
		if (threads + posts <= 0) throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
		// Deleting the OP also removes its replies. Otherwise never report a partial deletion as complete.
		if (threads == 0 && posts < selected.size()) {
			throw new ApiException(ChanConfiguration.get(this).getResources().getString(R.string.kohlchan_delete_partial));
		}
		return new SendDeletePostsResult();
	}

	private void checkResponse(JSONObject response, boolean solvingCaptcha)
			throws ApiException, HttpException, InvalidResponseException {
		String status = response.optString("status");
		if ("ok".equals(status)) return;
		String error = (status + " " + response.optString("data")).toLowerCase(Locale.US);
		if ("bypassable".equals(status) || "hashcash".equals(status)) {
			requestAccessAndStop();
		}
		if ("banned".equals(status) || "hashBan".equals(status)) throw new ApiException(ApiException.SEND_ERROR_BANNED);
		if (error.contains("captcha") || solvingCaptcha) throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
		if (error.contains("flood")) throw new ApiException(ApiException.SEND_ERROR_TOO_FAST);
		if (error.contains("board not found")) throw new ApiException(ApiException.SEND_ERROR_NO_BOARD);
		if (error.contains("thread not found")) throw new ApiException(ApiException.SEND_ERROR_NO_THREAD);
		if (error.contains("locked") || error.contains("closed")) throw new ApiException(ApiException.SEND_ERROR_CLOSED);
		if (error.contains("at least one file")) throw new ApiException(ApiException.SEND_ERROR_EMPTY_FILE);
		if (error.contains("either a message or a file")) throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		if (error.contains("file") && error.contains("too large")) throw new ApiException(ApiException.SEND_ERROR_FILE_TOO_BIG);
		if (error.contains("format that is not allowed")) throw new ApiException(ApiException.SEND_ERROR_FILE_NOT_SUPPORTED);
		if (error.contains("too many files")) throw new ApiException(ApiException.SEND_ERROR_FILES_TOO_MANY);
		if (error.contains("too long")) throw new ApiException(ApiException.SEND_ERROR_FIELD_TOO_LONG);
		if (!StringUtils.isEmpty(status)) {
			// Do not expose arbitrary server payloads (which can contain submitted fields) in diagnostics.
			throw new ApiException(ChanConfiguration.get(this).getResources().getString(R.string.kohlchan_request_failed));
		}
		throw new InvalidResponseException();
	}
}
