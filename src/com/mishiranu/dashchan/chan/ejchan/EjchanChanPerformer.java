package com.mishiranu.dashchan.chan.ejchan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
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
import chan.http.HttpValidator;
import chan.http.MultipartEntity;
import chan.http.UrlEncodedEntity;
import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.Logger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class EjchanChanPerformer extends ChanPerformer {
	private static final String COOKIE_EPASS = "epass";
	private static final String CAPTCHA_DATA_EPASS = "ejchanEpass";
	private static final String BOARD_AI = "ai";
	private static final String BOARD_AI_TITLE = "Искусственный интеллект";
	private static final Pattern CAPTCHA_IMAGE = Pattern.compile("base64,([^\\\"]+)");
	private static final int API_READ_TIMEOUT = 60000;

	private static final int JSON_OBJECT = '{';
	private static final int JSON_ARRAY = '[';

	private String lastEpassCode;
	private String lastEpassCookie;

	public EjchanChanPerformer() {
		try {
			registerFirewallResolver(new EjchanAnubisResolver());
		} catch (LinkageError e) {
			e.printStackTrace();
		}
	}

	private HttpRequest createJsonRequest(Uri uri, HttpRequest.Preset preset) {
		int readTimeout = API_READ_TIMEOUT;
		if (preset instanceof HttpRequest.TimeoutsPreset) {
			readTimeout = Math.max(readTimeout, ((HttpRequest.TimeoutsPreset) preset).getReadTimeout());
		}
		return new HttpRequest(uri, preset).setTimeouts(-1, readTimeout)
				.addHeader("Accept", "application/json")
				// Cloudflare sends compressed Ejchan API responses using chunked transfer. Some mobile/VPN
				// routes stall between chunks, while identity responses have a fixed Content-Length.
				.addHeader("Accept-Encoding", "identity");
	}

	private static String getHeader(HttpResponse response, String name) {
		for (Map.Entry<String, List<String>> entry : response.getHeaderFields().entrySet()) {
			if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
				return entry.getValue().get(0);
			}
		}
		return null;
	}

	private byte[] readJson(HttpResponse response, int expectedRoot) throws HttpException,
			InvalidResponseException {
		Logger.write(Logger.Type.DEBUG, "EjchanApi", "status", response.getResponseCode(),
				"type", getHeader(response, "Content-Type"), "encoding",
				getHeader(response, "Content-Encoding"), "length", response.getLength());
		byte[] bytes = response.readBytes();
		int index = 0;
		if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
				&& (bytes[2] & 0xff) == 0xbf) {
			index = 3;
		}
		while (index < bytes.length) {
			int value = bytes[index] & 0xff;
			if (value != ' ' && value != '\t' && value != '\r' && value != '\n') break;
			index++;
		}

		int root = index < bytes.length ? bytes[index] & 0xff : -1;
		String contentType = getHeader(response, "Content-Type");
		boolean html = root == '<' || contentType != null
				&& contentType.toLowerCase(Locale.US).startsWith("text/html");
		if (html || root != expectedRoot && contentType != null
				&& !contentType.toLowerCase(Locale.US).contains("json")) {
			String message = EjchanChanConfiguration.get(this).getResources()
					.getString(com.mishiranu.dashchan.R.string.ejchan_api_unavailable);
			throw new HttpException(0, message);
		}
		if (root != expectedRoot) throw new InvalidResponseException();
		return bytes;
	}

	private String readJsonString(HttpResponse response, int expectedRoot) throws HttpException,
			InvalidResponseException {
		return new String(readJson(response, expectedRoot), StandardCharsets.UTF_8);
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		try {
			JSONArray array = new JSONArray(readJsonString(
					createJsonRequest(locator.createBoardsApiUri(), data).perform(), JSON_ARRAY));
			ArrayList<Board> boards = new ArrayList<>();
			boolean hasAi = false;
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.getJSONObject(i);
				String boardName = CommonUtils.optJsonString(object, "board");
				String title = CommonUtils.optJsonString(object, "title");
				String subtitle = CommonUtils.optJsonString(object, "subtitle");
				if (!StringUtils.isEmpty(boardName) && !StringUtils.isEmpty(title)) {
					hasAi |= BOARD_AI.equals(boardName);
					boards.add(new Board(boardName, StringUtils.clearHtml(title),
							StringUtils.nullIfEmpty(StringUtils.clearHtml(subtitle))));
				}
			}
			// Ejchan exposes /ai/ through the regular JSON API, but currently omits it from boards.json.
			if (!hasAi) boards.add(new Board(BOARD_AI, BOARD_AI_TITLE));
			Collections.sort(boards);
			String category = EjchanChanConfiguration.get(this).getResources()
					.getString(com.mishiranu.dashchan.R.string.ejchan_boards);
			return new ReadBoardsResult(new BoardCategory(category, boards));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		Uri uri = locator.createThreadsApiUri(data.boardName, data.pageNumber, data.isCatalog());
		HttpResponse response = createJsonRequest(uri, data).setValidator(data.validator).perform();
		HttpValidator validator = response.getValidator();
		ArrayList<Posts> threads = new ArrayList<>();
		byte[] bytes = readJson(response, data.isCatalog() ? JSON_ARRAY : JSON_OBJECT);
		try (InputStream input = new ByteArrayInputStream(bytes); JsonSerial.Reader reader = JsonSerial.reader(input)) {
			if (data.isCatalog()) {
				reader.startArray();
				while (!reader.endStruct()) {
					reader.startObject();
					while (!reader.endStruct()) {
						if ("threads".equals(reader.nextName())) {
							reader.startArray();
							while (!reader.endStruct()) {
								threads.add(EjchanModelMapper.createThread(reader, locator, data.boardName, true));
							}
						} else {
							reader.skip();
						}
					}
				}
			} else {
				reader.startObject();
				while (!reader.endStruct()) {
					if ("threads".equals(reader.nextName())) {
						reader.startArray();
						while (!reader.endStruct()) {
							threads.add(EjchanModelMapper.createThread(reader, locator, data.boardName, false));
						}
					} else {
						reader.skip();
					}
				}
			}
			return new ReadThreadsResult(threads).setValidator(validator);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		HttpResponse response = createJsonRequest(locator.createThreadApiUri(data.boardName, data.threadNumber), data)
				.setValidator(data.validator).perform();
		ArrayList<Post> posts = new ArrayList<>();
		byte[] bytes = readJson(response, JSON_OBJECT);
		try (InputStream input = new ByteArrayInputStream(bytes); JsonSerial.Reader reader = JsonSerial.reader(input)) {
			reader.startObject();
			while (!reader.endStruct()) {
				if ("posts".equals(reader.nextName())) {
					reader.startArray();
					while (!reader.endStruct()) {
						posts.add(EjchanModelMapper.createPost(reader, locator, data.boardName, null));
					}
				} else {
					reader.skip();
				}
			}
			return new ReadPostsResult(new Posts(posts)).setValidator(response.getValidator()).setFullThread(true);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	private String verifyEpass(HttpRequest.Preset preset, String code) throws HttpException,
			InvalidResponseException {
		if (StringUtils.isEmpty(code)) return EjchanChanConfiguration.get(this).getCookie(COOKIE_EPASS);
		if (code.equals(lastEpassCode) && !StringUtils.isEmpty(lastEpassCookie)) return lastEpassCookie;
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		try {
			JSONObject object = new JSONObject(readJsonString(
					createJsonRequest(locator.createEpassUri(code), preset).perform(), JSON_OBJECT));
			String cookie = object.optBoolean("valid") ? CommonUtils.optJsonString(object, "cookie") : null;
			if (!StringUtils.isEmpty(cookie)) {
				lastEpassCode = code;
				lastEpassCookie = cookie;
				EjchanChanConfiguration.get(this).storeCookie(COOKIE_EPASS, cookie, "E-pass");
				return cookie;
			}
			return null;
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public CheckAuthorizationResult onCheckAuthorization(CheckAuthorizationData data) throws HttpException,
			InvalidResponseException {
		String code = data.authorizationData != null && data.authorizationData.length > 0
				? data.authorizationData[0] : null;
		return new CheckAuthorizationResult(verifyEpass(data, code) != null);
	}

	private static ReadCaptchaResult createEpassResult(String cookie) {
		CaptchaData captchaData = new CaptchaData();
		captchaData.put(CAPTCHA_DATA_EPASS, cookie);
		return new ReadCaptchaResult(CaptchaState.PASS, captchaData)
				.setValidity(EjchanChanConfiguration.Captcha.Validity.LONG_LIFETIME);
	}

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		String code = data.captchaPass != null && data.captchaPass.length > 0 ? data.captchaPass[0] : null;
		String epassCookie = verifyEpass(data, code);
		if (!StringUtils.isEmpty(epassCookie)) return createEpassResult(epassCookie);
		if (data.mayShowLoadButton) return new ReadCaptchaResult(CaptchaState.NEED_LOAD, null);

		EjchanChanLocator locator = EjchanChanLocator.get(this);
		try {
			JSONObject object = new JSONObject(readJsonString(
					createJsonRequest(locator.createCaptchaUri(), data).perform(), JSON_OBJECT));
			String cookie = CommonUtils.optJsonString(object, "cookie");
			String html = CommonUtils.optJsonString(object, "captchahtml");
			Matcher matcher = CAPTCHA_IMAGE.matcher(StringUtils.emptyIfNull(html));
			if (StringUtils.isEmpty(cookie) || !matcher.find()) throw new InvalidResponseException();
			byte[] bytes = Base64.decode(matcher.group(1), Base64.DEFAULT);
			Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
			if (bitmap == null) throw new InvalidResponseException();
			CaptchaData captchaData = new CaptchaData();
			captchaData.put(CaptchaData.CHALLENGE, cookie);
			return new ReadCaptchaResult(CaptchaState.CAPTCHA, captchaData)
					.setCaptchaType(EjchanChanConfiguration.CAPTCHA_TYPE_EJCHAN)
					.setInput(EjchanChanConfiguration.Captcha.Input.LATIN)
					.setValidity(EjchanChanConfiguration.Captcha.Validity.SHORT_LIFETIME).setImage(bitmap);
		} catch (JSONException | IllegalArgumentException e) {
			throw new InvalidResponseException(e);
		}
	}

	private static final String[] KNOWN_POST_FIELDS = {"board", "thread", "name", "email", "subject", "body",
			"password", "file", "file2", "file3", "file4", "spoiler", "json_response", "captcha_text",
			"captcha_cookie", "op_check", "post"};

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		MultipartEntity entity = new MultipartEntity();
		entity.add("board", data.boardName);
		entity.add("thread", data.threadNumber);
		entity.add("name", data.name);
		entity.add("email", data.optionSage ? "sage" : data.email);
		entity.add("subject", data.subject);
		entity.add("body", StringUtils.emptyIfNull(data.comment));
		entity.add("password", data.password);
		boolean spoiler = data.optionSpoiler;
		if (data.attachments != null) {
			for (int i = 0; i < data.attachments.length; i++) {
				SendPostData.Attachment attachment = data.attachments[i];
				attachment.addToEntity(entity, "file" + (i > 0 ? i + 1 : ""));
				spoiler |= attachment.optionSpoiler;
			}
		}
		if (spoiler) entity.add("spoiler", "on");
		entity.add("json_response", "1");

		String epassCookie = null;
		if (data.captchaData != null) {
			epassCookie = data.captchaData.get(CAPTCHA_DATA_EPASS);
			if (StringUtils.isEmpty(epassCookie)) {
				entity.add("captcha_cookie", data.captchaData.get(CaptchaData.CHALLENGE));
				entity.add("captcha_text", data.captchaData.get(CaptchaData.INPUT));
			}
		}
		if (StringUtils.isEmpty(epassCookie)) {
			epassCookie = EjchanChanConfiguration.get(this).getCookie(COOKIE_EPASS);
		}

		Uri contentUri = data.threadNumber != null ? locator.createThreadUri(data.boardName, data.threadNumber)
				: locator.createBoardUri(data.boardName, 0);
		String responseText = new HttpRequest(contentUri, data).addCookie(COOKIE_EPASS, epassCookie)
				.perform().readString();
		try {
			String submitValue = EjchanAntispamParser.parseAndApply(responseText, entity, KNOWN_POST_FIELDS);
			if (StringUtils.isEmpty(submitValue)) {
				submitValue = data.threadNumber != null ? "Новый ответ" : "Новый тред";
			}
			entity.add("post", submitValue);
		} catch (ParseException e) {
			throw new InvalidResponseException(e);
		}

		JSONObject object;
		try {
			object = new JSONObject(readJsonString(createJsonRequest(locator.createPostEndpointUri(), data)
					.setPostMethod(entity)
					.addCookie(COOKIE_EPASS, epassCookie).addHeader("Referer", contentUri.toString())
					.setRedirectHandler(HttpRequest.RedirectHandler.STRICT).perform(), JSON_OBJECT));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
		String redirect = CommonUtils.optJsonString(object, "redirect");
		if (!StringUtils.isEmpty(redirect)) {
			Uri redirectUri = Uri.parse(redirect);
			String threadNumber = locator.getThreadNumber(redirectUri);
			String postNumber = locator.getPostNumber(redirectUri);
			return new SendPostResult(!StringUtils.isEmpty(threadNumber) ? threadNumber : data.threadNumber, postNumber);
		}
		handlePostError(CommonUtils.optJsonString(object, "error"));
		throw new InvalidResponseException();
	}

	private static void handlePostError(String message) throws ApiException {
		if (StringUtils.isEmpty(message)) return;
		String lower = StringUtils.clearHtml(message).toLowerCase();
		int type = lower.contains("captcha") || lower.contains("verification")
				? ApiException.SEND_ERROR_CAPTCHA
				: lower.contains("flood") || lower.contains("too fast")
				? ApiException.SEND_ERROR_TOO_FAST
				: lower.contains("locked") ? ApiException.SEND_ERROR_CLOSED
				: lower.contains("too big") || lower.contains("file size")
				? ApiException.SEND_ERROR_FILE_TOO_BIG : 0;
		if (type != 0) throw new ApiException(type);
		throw new ApiException(StringUtils.clearHtml(message));
	}

	@Override
	public SendDeletePostsResult onSendDeletePosts(SendDeletePostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		UrlEncodedEntity entity = new UrlEncodedEntity("delete", "1", "board", data.boardName,
				"password", data.password, "json_response", "1");
		for (String postNumber : data.postNumbers) entity.add("delete_" + postNumber, "1");
		if (data.optionFilesOnly) entity.add("file", "on");
		JSONObject object = postAction(data, entity);
		if (object.optBoolean("success")) return new SendDeletePostsResult();
		String error = CommonUtils.optJsonString(object, "error");
		if (!StringUtils.isEmpty(error)) throw new ApiException(StringUtils.clearHtml(error));
		throw new InvalidResponseException();
	}

	@Override
	public SendReportPostsResult onSendReportPosts(SendReportPostsData data) throws HttpException, ApiException,
			InvalidResponseException {
		UrlEncodedEntity entity = new UrlEncodedEntity("report", "1", "board", data.boardName,
				"reason", StringUtils.emptyIfNull(data.comment), "json_response", "1");
		for (String postNumber : data.postNumbers) entity.add("delete_" + postNumber, "1");
		JSONObject object = postAction(data, entity, HttpRequest.RedirectHandler.NONE);
		if (object.optBoolean("success")) return new SendReportPostsResult();
		String error = CommonUtils.optJsonString(object, "error");
		if (!StringUtils.isEmpty(error)) throw new ApiException(StringUtils.clearHtml(error));
		throw new InvalidResponseException();
	}

	private JSONObject postAction(HttpRequest.Preset preset, UrlEncodedEntity entity) throws HttpException,
			InvalidResponseException {
		return postAction(preset, entity, HttpRequest.RedirectHandler.STRICT);
	}

	private JSONObject postAction(HttpRequest.Preset preset, UrlEncodedEntity entity,
			HttpRequest.RedirectHandler redirectHandler) throws HttpException, InvalidResponseException {
		EjchanChanLocator locator = EjchanChanLocator.get(this);
		try {
			return new JSONObject(readJsonString(createJsonRequest(locator.createPostEndpointUri(), preset)
					.setPostMethod(entity)
					.addCookie(COOKIE_EPASS, EjchanChanConfiguration.get(this).getCookie(COOKIE_EPASS))
					.setRedirectHandler(redirectHandler).perform(), JSON_OBJECT));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}
}
