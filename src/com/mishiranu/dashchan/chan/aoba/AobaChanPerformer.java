package com.mishiranu.dashchan.chan.aoba;

import android.graphics.Bitmap;
import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.HttpValidator;
import chan.http.MultipartEntity;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.NetworkDiagnostics;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public class AobaChanPerformer extends ChanPerformer {
	private static final int PAGE_SIZE = 10;
	private static final String SESSION_COOKIE = "PHPSESSID";
	private static final String SESSION = "aoba_session";
	private static final String LANGUAGE = "aoba_language";
	private static final String CAPTCHA_LANGUAGE = "captchalang";
	private static final String CYRILLIC_CAPTCHA = "ru";

	private AobaChanLocator locator() {
		return (AobaChanLocator) get().locator;
	}

	private static JSONObject json(HttpResponse response) throws HttpException, InvalidResponseException {
		try {
			return new JSONObject(response.readString());
		} catch (JSONException e) {
			NetworkDiagnostics trace = response.getNetworkDiagnostics();
			if (trace != null) trace.failure("aoba_invalid_json", e);
			throw new InvalidResponseException(e);
		}
	}

	private static InvalidResponseException invalidResponse(HttpResponse response, String reason) {
		NetworkDiagnostics trace = response.getNetworkDiagnostics();
		if (trace != null) trace.event("aoba_invalid_response reason=" + reason);
		return new InvalidResponseException();
	}

	@Override
	public ReadBoardsResult onReadBoards(ReadBoardsData data) throws HttpException, InvalidResponseException {
		Uri uri = locator().createApiUri("get_boards", "{}");
		try {
			JSONObject boards = json(new HttpRequest(uri, data).perform()).getJSONObject("result");
			ArrayList<Board> result = new ArrayList<>();
			for (Iterator<String> iterator = boards.keys(); iterator.hasNext();) {
				String name = iterator.next();
				if (name.matches("[a-zA-Z0-9_]+")) {
					result.add(new Board(name, boards.getJSONObject(name).optString("name", name)));
				}
			}
			if (result.isEmpty()) throw new InvalidResponseException();
			return new ReadBoardsResult(new BoardCategory(null, result));
		} catch (JSONException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		try {
			JSONObject params = new JSONObject().put("board", data.boardName)
					.put("start", (long) Math.max(0, data.pageNumber) * PAGE_SIZE)
					.put("threadnum", PAGE_SIZE).put("previewnum", 3).put("skipreflinks", true);
			Uri uri = locator().createApiUri("get_part_of_board", params.toString());
			HttpResponse response = new HttpRequest(uri, data).setValidator(data.validator).perform();
			HttpValidator validator = response.getValidator();
			JSONArray items = json(response).getJSONArray("result");
			ArrayList<Posts> threads = new ArrayList<>();
			for (int i = 0; i < items.length(); i++) {
				JSONObject item = items.getJSONObject(i);
				JSONObject op = item.getJSONObject("op");
				String thread = op.getString("id");
				Post first = readPost(op, data.boardName, thread);
				JSONObject flags = item.optJSONObject("opflags");
				if (flags != null) first.setSticky(isTrue(flags, "stickied")).setClosed(isTrue(flags, "locked"));
				ArrayList<Post> preview = new ArrayList<>();
				preview.add(first);
				JSONArray replies = item.optJSONArray("lastreplies");
				for (int j = 0; replies != null && j < replies.length(); j++) {
					preview.add(readPost(replies.getJSONObject(j), data.boardName, thread));
				}
				threads.add(new Posts(preview).addPostsCount(1 + item.optInt("numreplies", preview.size() - 1))
						.addFilesCount(item.optInt("numpicreplies", 0) + first.getAttachmentsCount()));
			}
			return new ReadThreadsResult(threads).setValidator(validator);
		} catch (JSONException | IllegalArgumentException e) {
			throw new InvalidResponseException(e);
		}
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		try {
			JSONObject params = new JSONObject().put("board", data.boardName).put("thread_id", data.threadNumber)
					.put("replyformat", "array").put("skipreflinks", true);
			Uri uri = locator().createApiUri("get_thread", params.toString());
			HttpResponse response = new HttpRequest(uri, data).setValidator(data.validator).perform();
			HttpValidator validator = response.getValidator();
			JSONArray items = json(response).getJSONArray("result");
			if (items.length() == 0) throw new InvalidResponseException();
			ArrayList<Post> posts = new ArrayList<>();
			for (int i = 0; i < items.length(); i++) {
				posts.add(readPost(items.getJSONObject(i), data.boardName, data.threadNumber));
			}
			if (!data.threadNumber.equals(posts.get(0).getPostNumber())) throw new InvalidResponseException();
			return new ReadPostsResult(posts).setValidator(validator).setFullThread(true);
		} catch (JSONException | IllegalArgumentException e) {
			throw new InvalidResponseException(e);
		}
	}

	private Post readPost(JSONObject item, String board, String thread) throws JSONException {
		String number = item.getString("id");
		Post post = new Post().setPostNumber(number).setThreadNumber(thread)
				.setParentPostNumber(thread.equals(number) ? null : thread)
				.setSubject(item.optString("subject")).setName(item.optString("name"))
				.setTripcode(item.optString("tripcode")).setEmail(item.optString("email"))
				.setSage("sage".equalsIgnoreCase(item.optString("email")))
				.setTimestamp(item.optLong("datetime") * 1000L).setComment(item.optString("text"));
		String filename = item.optString("filename");
		String ext = item.optString("filetype").toLowerCase(Locale.ROOT);
		if (filename.matches("[0-9]+") && ext.matches("jpe?g|png|gif|webp|webm|mp4|m4a|mp3|ogg")) {
			AobaChanLocator locator = locator();
			FileAttachment file = new FileAttachment().setFileUri(locator,
					locator.createFileUri(board, "src", filename + "." + ext))
					.setWidth(item.optInt("pic_w")).setHeight(item.optInt("pic_h"))
					.setSize(item.optInt("filesize")).setSpoiler(isTrue(item, "spoiler"));
			if (item.optInt("thumb_w") > 0 && ext.matches("jpe?g|png|gif|webp")) {
				file.setThumbnailUri(locator, locator.createFileUri(board, "thumb", filename + "s." + ext));
			}
			post.setAttachments(file);
		}
		return post;
	}

	private static boolean isTrue(JSONObject item, String key) {
		return item.optBoolean(key) || item.optInt(key) != 0;
	}

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		if (data.mayShowLoadButton) return new ReadCaptchaResult(CaptchaState.NEED_LOAD, null);
		AobaChanLocator locator = locator();
		Uri page = data.threadNumber != null ? locator.createThreadUri(data.boardName, data.threadNumber)
				: locator.createBoardUri(data.boardName, 0);
		data.holder.setMayResolveFirewallBlock(false);
		// Aoba offers a Cyrillic image in its captcha settings alongside the browser widget.
		HttpResponse response = new HttpRequest(page, data).addCookie(CAPTCHA_LANGUAGE, CYRILLIC_CAPTCHA).perform();
		String session = response.getCookieValue(SESSION_COOKIE);
		Element form = Jsoup.parse(response.readString(), page.toString()).selectFirst("form#postform");
		if (form == null || !locator.createSendUri().equals(Uri.parse(form.absUrl("action")))) {
			throw invalidResponse(response, "captcha_form");
		}
		CaptchaData captcha = new CaptchaData();
		Element id = form.selectFirst("input[name=captchaid]");
		Element image = form.selectFirst("img.captchaimage[src]");
		if (id == null && image == null && form.selectFirst("input[name=captcha]") == null) {
			captcha.put(SESSION, session);
			return new ReadCaptchaResult(CaptchaState.SKIP, captcha);
		}
		if (id == null || id.val().isEmpty() || image == null) throw invalidResponse(response, "captcha_fields");
		Element language = form.selectFirst("input[name=" + CAPTCHA_LANGUAGE + "]");
		if (language == null || !CYRILLIC_CAPTCHA.equals(language.val())) {
			throw invalidResponse(response, "captcha_language");
		}
		Uri imageUri = Uri.parse(image.absUrl("src"));
		if (!"https".equals(imageUri.getScheme()) || !page.getHost().equals(imageUri.getHost())
				|| !"/captcha.php".equals(imageUri.getPath())
				|| !id.val().equals(imageUri.getQueryParameter("captchaid"))) {
			throw invalidResponse(response, "captcha_image_address");
		}
		HttpResponse imageResponse = new HttpRequest(imageUri, data).addCookie(SESSION_COOKIE, session)
				.addCookie(CAPTCHA_LANGUAGE, language.val())
				.addHeader("Referer", page.toString()).setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		String imageSession = imageResponse.getCookieValue(SESSION_COOKIE);
		if (imageSession != null) session = imageSession;
		Bitmap bitmap = imageResponse.readBitmap();
		if (bitmap == null) throw invalidResponse(imageResponse, "captcha_image");
		if (session == null) throw invalidResponse(imageResponse, "captcha_session");
		captcha.put(SESSION, session);
		captcha.put(CaptchaData.CHALLENGE, id.val());
		captcha.put(LANGUAGE, language.val());
		return new ReadCaptchaResult(CaptchaState.CAPTCHA, captcha).setImage(bitmap)
				.setValidity(ChanConfiguration.Captcha.Validity.SHORT_LIFETIME);
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		AobaChanLocator locator = locator();
		Uri page = data.threadNumber != null ? locator.createThreadUri(data.boardName, data.threadNumber)
				: locator.createBoardUri(data.boardName, 0);
		MultipartEntity entity = new MultipartEntity();
		entity.add("board", data.boardName);
		entity.add("replythread", data.threadNumber != null ? data.threadNumber : "0");
		entity.add("subject", StringUtils.emptyIfNull(data.subject));
		entity.add("message", StringUtils.emptyIfNull(data.comment));
		entity.add("postpassword", StringUtils.emptyIfNull(data.password));
		entity.add("attach_type", "file");
		entity.add("through_js", "1");
		entity.add("redirecttothread", "1");
		if (data.optionSage) entity.add("em", "sage");
		if (data.attachments != null && data.attachments.length > 0) {
			if (data.attachments.length > 1) throw new ApiException(ApiException.SEND_ERROR_FILES_TOO_MANY);
			SendPostData.Attachment attachment = data.attachments[0];
			attachment.addToEntity(entity, "imagefile");
			if (attachment.optionSpoiler || data.optionSpoiler) entity.add("picspoiler", "1");
		}
		String session = null;
		String language = null;
		if (data.captchaData != null) {
			session = data.captchaData.get(SESSION);
			language = data.captchaData.get(LANGUAGE);
			entity.add("captchaid", data.captchaData.get(CaptchaData.CHALLENGE));
			entity.add("captcha", data.captchaData.get(CaptchaData.INPUT));
			entity.add(CAPTCHA_LANGUAGE, language);
		}
		data.getHolder().setMayResolveFirewallBlock(false);
		HttpResponse httpResponse = new HttpRequest(locator.createSendUri(), data)
				.addCookie(SESSION_COOKIE, session).addCookie(CAPTCHA_LANGUAGE, language)
				.addHeader("Referer", page.toString()).setPostMethod(entity)
				.setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		JSONObject response = json(httpResponse);
		String error = Jsoup.parse(response.optString("error")).text();
		if (!error.isEmpty()) {
			String lower = error.toLowerCase(Locale.ROOT);
			if (lower.contains("captcha") || lower.contains("капч") || lower.contains("каптч")) {
				throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
			}
			throw new ApiException(error);
		}
		Uri target = Uri.parse(response.optString("redirect_to"));
		String thread = locator.getThreadNumber(target);
		long number = response.optLong("newpostnum", 0);
		if (number <= 0 || !locator.isThreadUri(target) || !data.boardName.equals(locator.getBoardName(target))
				|| (data.threadNumber != null && !data.threadNumber.equals(thread))) {
			throw invalidResponse(httpResponse, "post_result");
		}
		return new SendPostResult(thread, Long.toString(number));
	}
}
