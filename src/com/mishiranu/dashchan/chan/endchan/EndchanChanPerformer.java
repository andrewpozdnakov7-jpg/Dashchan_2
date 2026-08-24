package com.mishiranu.dashchan.chan.endchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import chan.content.ApiException;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.SimpleEntity;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EndchanChanPerformer extends ChanPerformer {
	private static final String REQUIRE_REPORT = "report";
	private static final String REQUIRE_IP_BLOCK_BYPASS = "ip_block_bypass";
	private static final String KEY_IP_BLOCK_BYPASS = "ip_block_bypass_key";

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		try {
			if (data.isCatalog()) {
				JSONArray array = new JSONArray(new HttpRequest(locator.buildPath(data.boardName, "catalog.json"), data)
						.setValidator(data.validator).perform().readString());
				return array.length() > 0 ? new ReadThreadsResult(EndchanModelMapper.createThreads(array, locator)) : null;
			}
			JSONObject object = new JSONObject(new HttpRequest(locator.buildPath(data.boardName,
					(data.pageNumber + 1) + ".json"), data).setValidator(data.validator).perform().readString());
			if (data.pageNumber == 0) {
				EndchanChanConfiguration configuration = ChanConfiguration.get(this);
				configuration.updateFromThreadsJson(data.boardName, object, true);
			}
			JSONArray array = object.optJSONArray("threads");
			return array != null && array.length() > 0
					? new ReadThreadsResult(EndchanModelMapper.createThreads(array, locator)) : null;
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		try {
			JSONObject object = new JSONObject(new HttpRequest(locator.buildPath(data.boardName, "res",
					data.threadNumber + ".json"), data).setValidator(data.validator).perform().readString());
			return new ReadPostsResult(EndchanModelMapper.createPosts(object, locator));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
		try {
			Uri uri = locator.buildQuery("boards.js", "json", "1");
			JSONObject object = new JSONObject(new HttpRequest(uri, data).perform().readString());
			ArrayList<Board> boards = new ArrayList<>();
			appendBoards(object, boards, new HashSet<>(), configuration);
			if (boards.isEmpty()) throw new InvalidResponseException();
			return new ReadBoardsResult(new BoardCategory("Endchan", boards));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadUserBoardsResult onReadUserBoards(ReadUserBoardsData data) throws HttpException,
			InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
		try {
			Uri uri = locator.buildQuery("boards.js", "json", "1");
			JSONObject object = new JSONObject(new HttpRequest(uri, data).perform().readString());
			HashSet<String> boardNames = new HashSet<>();
			ArrayList<Board> boards = new ArrayList<>();
			for (int page = 0, count = object.getInt("pageCount"); page < count; page++) {
				if (page > 0) {
					uri = locator.buildQuery("boards.js", "json", "1", "page", Integer.toString(page + 1));
					object = new JSONObject(new HttpRequest(uri, data).perform().readString());
				}
				appendBoards(object, boards, boardNames, configuration);
			}
			return new ReadUserBoardsResult(boards);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	private static void appendBoards(JSONObject object, Collection<Board> boards, HashSet<String> boardNames,
			EndchanChanConfiguration configuration) throws JSONException {
		JSONArray array = object.getJSONArray("boards");
		for (int i = 0; i < array.length(); i++) {
			JSONObject board = array.getJSONObject(i);
			String name = CommonUtils.optJsonString(board, "boardUri");
			if (!StringUtils.isEmpty(name) && boardNames.add(name)) {
				String title = CommonUtils.optJsonString(board, "boardName");
				boards.add(new Board(name, StringUtils.isEmpty(title) ? name : title,
						CommonUtils.optJsonString(board, "boardDescription")));
				configuration.updateFromThreadsJson(name, board, false);
			}
		}
	}

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		boolean needCaptcha = REQUIRE_REPORT.equals(data.requirement) || REQUIRE_IP_BLOCK_BYPASS.equals(data.requirement);
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		if (!needCaptcha) {
			EndchanChanConfiguration configuration = ChanConfiguration.get(this);
			Boolean required = configuration.isCaptchaRequired(data.boardName,
					StringUtils.isEmpty(data.threadNumber));
			if (required != null) {
				needCaptcha = required;
			} else {
				Uri pageUri = !StringUtils.isEmpty(data.threadNumber)
						? locator.createThreadUri(data.boardName, data.threadNumber)
						: locator.createBoardUri(data.boardName, 0);
				needCaptcha = new HttpRequest(pageUri, data).perform().readString().contains("id=\"captchaDiv\"");
			}
		}
		if (!needCaptcha) return new ReadCaptchaResult(CaptchaState.SKIP, null);

		Uri captchaUri = StringUtils.isEmpty(data.boardName) ? locator.buildPath("captcha.js")
				: locator.buildQuery("captcha.js", "boardUri", data.boardName);
		HttpResponse response = new HttpRequest(captchaUri, data).perform();
		Bitmap image = response.readBitmap();
		String captchaId = response.getCookieValue("captchaid");
		if (image == null || StringUtils.isEmpty(captchaId)) throw new InvalidResponseException();
		int[] pixels = new int[image.getWidth() * image.getHeight()];
		image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = (0xff - Math.max(Color.red(pixels[i]), Color.blue(pixels[i]))) << 24;
		}
		Bitmap converted = Bitmap.createBitmap(pixels, image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
		image.recycle();
		Bitmap trimmed = CommonUtils.trimBitmap(converted, 0x00000000);
		if (trimmed == null) {
			trimmed = converted;
		} else if (trimmed != converted) {
			converted.recycle();
		}
		CaptchaData captchaData = new CaptchaData();
		captchaData.put(CaptchaData.CHALLENGE, captchaId);
		return new ReadCaptchaResult(CaptchaState.CAPTCHA, captchaData).setImage(trimmed);
	}

	private static String trimPassword(String password) {
		return password != null && password.length() > 8 ? password.substring(0, 8) : password;
	}

	private static byte[] readAttachment(SendPostData.Attachment attachment)
			throws InvalidResponseException {
		long size = attachment.getSize();
		int initialSize = size > 0 ? (int) Math.min(size, 1024 * 1024) : 8192;
		try (InputStream input = attachment.openInputSteamForSending();
				ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize)) {
			byte[] buffer = new byte[32 * 1024];
			for (int count; (count = input.read(buffer)) >= 0;) {
				if (count > 0) output.write(buffer, 0, count);
			}
			return output.toByteArray();
		} catch (IOException e) {
			throw new InvalidResponseException(e);
		}
	}

	private static String digestToString(byte[] digest) {
		StringBuilder builder = new StringBuilder(digest.length * 2);
		for (byte value : digest) builder.append(String.format(Locale.US, "%02x", value & 0xff));
		return builder.toString();
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		return sendPost(data, true);
	}

	private SendPostResult sendPost(SendPostData data, boolean allowBypass) throws HttpException, ApiException,
			InvalidResponseException {
		EndchanChanLocator locator = EndchanChanLocator.get(this);
		EndchanChanConfiguration configuration = EndchanChanConfiguration.get(this);
		JSONObject request = new JSONObject();
		JSONObject parameters = new JSONObject();
		try {
			String bypassId = configuration.get(null, KEY_IP_BLOCK_BYPASS, null);
			if (!StringUtils.isEmpty(bypassId)) request.put("bypassId", bypassId);
			request.put("parameters", parameters);
			parameters.put("boardUri", data.boardName);
			if (!StringUtils.isEmpty(data.threadNumber)) parameters.put("threadId", data.threadNumber);
			if (!StringUtils.isEmpty(data.name)) parameters.put("name", data.name);
			if (!StringUtils.isEmpty(data.subject)) parameters.put("subject", data.subject);
			if (!StringUtils.isEmpty(data.password)) parameters.put("password", trimPassword(data.password));
			if (data.optionSage) {
				parameters.put("email", "sage");
			} else if (!StringUtils.isEmpty(data.email)) {
				parameters.put("email", data.email);
			}
			if (!StringUtils.isEmpty(data.userIcon)) parameters.put("flag", data.userIcon);
			parameters.put("message", StringUtils.emptyIfNull(data.comment));
			if (data.captchaData != null) {
				String captchaId = data.captchaData.get(CaptchaData.CHALLENGE);
				if (!StringUtils.isEmpty(captchaId)) {
					request.put("captchaId", captchaId);
					parameters.put("captcha", StringUtils.emptyIfNull(data.captchaData.get(CaptchaData.INPUT)));
				}
			}
			if (data.attachments != null && data.attachments.length > 0) {
				JSONArray files = new JSONArray();
				MessageDigest md5;
				try {
					md5 = MessageDigest.getInstance("MD5");
				} catch (NoSuchAlgorithmException e) {
					throw new AssertionError(e);
				}
				for (SendPostData.Attachment attachment : data.attachments) {
					byte[] bytes = readAttachment(attachment);
					String mimeType = attachment.getMimeType();
					String digest = digestToString(md5.digest(bytes));
					String known = new HttpRequest(locator.buildQuery("checkFileIdentifier.js", "identifier",
							digest + "-" + mimeType.replace("/", "")), data).perform().readString().trim();
					JSONObject file = new JSONObject();
					if ("false".equals(known)) {
						file.put("content", "data:" + mimeType + ";base64,"
								+ Base64.encodeToString(bytes, Base64.NO_WRAP));
					} else if ("true".equals(known)) {
						file.put("mime", mimeType);
						file.put("md5", digest);
					} else {
						throw new InvalidResponseException();
					}
					file.put("name", attachment.getFileName());
					if (attachment.optionSpoiler) file.put("spoiler", true);
					files.put(file);
				}
				parameters.put("files", files);
			}
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		JSONObject response = performJsonPost(locator.buildPath(".api",
				data.threadNumber != null ? "replyThread" : "newThread"), request, data);
		String status = response.optString("status");
		if ("ok".equals(status)) {
			String postNumber = Integer.toString(response.optInt("data"));
			String threadNumber = data.threadNumber;
			if (threadNumber == null) {
				threadNumber = postNumber;
				postNumber = null;
			}
			CommonUtils.sleepMaxRealtime(SystemClock.elapsedRealtime(), 2000);
			return new SendPostResult(threadNumber, postNumber);
		}
		if ("bypassable".equals(status) && allowBypass) {
			JSONObject bypass = bypassIpBlock(data);
			if (bypass != null && "ok".equals(bypass.optString("status"))) {
				configuration.set(null, KEY_IP_BLOCK_BYPASS, bypass.optString("data"));
				return sendPost(data, false);
			}
			throw new ApiException(configuration.getResources().getString(R.string.endchan_ip_block_bypass_failed));
		}
		if ("banned".equals(status)) throw new ApiException(ApiException.SEND_ERROR_BANNED);
		String message = response.optString("data");
		if (message.contains("Wrong captcha") || message.contains("Expired captcha")) {
			throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
		} else if (message.contains("Flood detected")) {
			throw new ApiException(ApiException.SEND_ERROR_TOO_FAST);
		} else if (message.contains("Either a message or a file is required") || "message".equals(message)) {
			throw new ApiException(ApiException.SEND_ERROR_EMPTY_COMMENT);
		} else if (message.contains("Board not found")) {
			throw new ApiException(ApiException.SEND_ERROR_NO_BOARD);
		} else if (message.contains("Thread not found")) {
			throw new ApiException(ApiException.SEND_ERROR_NO_THREAD);
		} else if (!StringUtils.isEmpty(status) || !StringUtils.isEmpty(message)) {
			throw new ApiException(status + ": " + message);
		}
		throw new InvalidResponseException();
	}

	private static JSONObject performJsonPost(Uri uri, JSONObject object, HttpRequest.Preset preset)
			throws HttpException, InvalidResponseException {
		return performJsonPost(uri, object, preset, HttpRequest.RedirectHandler.STRICT);
	}

	private static JSONObject performJsonPost(Uri uri, JSONObject object, HttpRequest.Preset preset,
			HttpRequest.RedirectHandler redirectHandler) throws HttpException, InvalidResponseException {
		SimpleEntity entity = new SimpleEntity();
		entity.setContentType("application/json; charset=utf-8");
		entity.setData(object.toString());
		try {
			return new JSONObject(new HttpRequest(uri, preset).setPostMethod(entity)
					.setRedirectHandler(redirectHandler).perform().readString());
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	private JSONObject bypassIpBlock(HttpRequest.Preset preset) throws HttpException, InvalidResponseException {
		CaptchaData captchaData = requireUserCaptcha(REQUIRE_IP_BLOCK_BYPASS, null, null, false);
		if (captchaData == null) return null;
		String input = captchaData.get(CaptchaData.INPUT);
		String id = captchaData.get(CaptchaData.CHALLENGE);
		if (StringUtils.isEmpty(input) || StringUtils.isEmpty(id)) return null;
		try {
			JSONObject parameters = new JSONObject();
			parameters.put("captcha", input);
			JSONObject request = new JSONObject();
			request.put("parameters", parameters);
			request.put("captchaId", id);
			return performJsonPost(EndchanChanLocator.get(this).buildPath(".api", "renewBypass"), request, preset);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	private static void fillDeleteReportPostings(JSONObject parameters, String boardName, String threadNumber,
			Collection<String> postNumbers) throws JSONException {
		JSONArray postings = new JSONArray();
		for (String postNumber : postNumbers) {
			JSONObject post = new JSONObject();
			post.put("board", boardName);
			post.put("thread", threadNumber);
			if (!postNumber.equals(threadNumber)) post.put("post", postNumber);
			postings.put(post);
		}
		parameters.put("postings", postings);
	}

	@Override
	public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		try {
			JSONObject parameters = new JSONObject();
			parameters.put("password", trimPassword(data.password));
			parameters.put("deleteMedia", true);
			if (data.optionFilesOnly) parameters.put("deleteUploads", true);
			fillDeleteReportPostings(parameters, data.boardName, data.threadNumber, data.postNumbers);
			JSONObject request = new JSONObject();
			request.put("parameters", parameters);
			JSONObject response = performJsonPost(EndchanChanLocator.get(this).buildPath(".api", "deleteContent"),
					request, data);
			if ("error".equals(response.optString("status"))) {
				String message = response.optString("data");
				if (message.contains("Invalid account")) throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
				throw new ApiException(message);
			}
			JSONObject result = response.optJSONObject("data");
			if (result == null) throw new InvalidResponseException();
			if (result.optInt("removedThreads") + result.optInt("removedPosts") > 0) {
				return new SendDeletePostsResult();
			}
			throw new ApiException(ApiException.DELETE_ERROR_PASSWORD);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public SendReportPostsResult onSendReportPosts(SendReportPostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		JSONObject request = new JSONObject();
		JSONObject parameters = new JSONObject();
		try {
			request.put("parameters", parameters);
			parameters.put("reason", StringUtils.emptyIfNull(data.comment));
			if (data.options != null && data.options.contains("global")) parameters.put("global", true);
			fillDeleteReportPostings(parameters, data.boardName, data.threadNumber, data.postNumbers);
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		for (int captchaAttempt = 1; captchaAttempt <= 5; captchaAttempt++) {
			CaptchaData captchaData = requireUserCaptcha(REQUIRE_REPORT, data.boardName, data.threadNumber,
					captchaAttempt > 1);
			if (captchaData == null) throw new ApiException(ApiException.REPORT_ERROR_NO_ACCESS);
			try {
				request.put("captchaId", captchaData.get(CaptchaData.CHALLENGE));
				parameters.put("captcha", StringUtils.emptyIfNull(captchaData.get(CaptchaData.INPUT)));
			} catch (JSONException e) {
				throw new InvalidResponseException(e);
			}
			JSONObject response = performJsonPost(EndchanChanLocator.get(this).buildPath(".api", "reportContent"),
					request, data, HttpRequest.RedirectHandler.NONE);
			String status = response.optString("status");
			if ("ok".equals(status)) return new SendReportPostsResult();
			String message = response.optString("data");
			if ((message.contains("Wrong captcha") || message.contains("Expired captcha"))
					&& captchaAttempt < 5) continue;
			if (!StringUtils.isEmpty(message)) throw new ApiException(status + ": " + message);
			throw new InvalidResponseException();
		}
		throw new AssertionError();
	}
}
