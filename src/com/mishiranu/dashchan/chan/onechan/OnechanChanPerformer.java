package com.mishiranu.dashchan.chan.onechan;

import android.graphics.Bitmap;
import android.net.Uri;
import chan.content.ApiException;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.InvalidResponseException;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.http.HttpValidator;
import chan.http.UrlEncodedEntity;
import chan.util.StringUtils;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class OnechanChanPerformer extends ChanPerformer {
	private static final String SESSION_COOKIE = "PHPSESSID";
	private static final String SITE_SESSION_COOKIE = "key";
	private static final String SESSION = "onechan_session";
	private static final String SITE_SESSION = "onechan_site_session";
	private static final String ENTRY = ".b-blog-entry[id]";
	private static final String COMMENT = ".b-comment[id]";
	private static final String NUMBER = "[1-9][0-9]*";
	private static final Pattern REPLY_COUNT = Pattern.compile("(?iu)обсуждение:\\s*([0-9]+)");
	private static final Pattern DATE = Pattern.compile(
			"([0-9]{1,2})\\s+([а-яА-ЯёЁ]+)(?:\\s+([0-9]{4}))?\\s*@\\s*([0-9]{2}):([0-9]{2}):([0-9]{2})");
	private static final Pattern MEDIA = Pattern.compile("(?i).*\\.(?:jpe?g|png|gif|webp|webm|mp4)$");
	private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня",
			"июля", "августа", "сентября", "октября", "ноября", "декабря"};

	private OnechanChanLocator locator() {
		return (OnechanChanLocator) get().locator;
	}

	private static Document readDocument(HttpResponse response, Uri page) throws HttpException {
		return Jsoup.parse(response.readString(), page.toString());
	}

	@Override
	public ReadThreadsResult onReadThreads(ReadThreadsData data) throws HttpException, InvalidResponseException {
		OnechanChanLocator locator = locator();
		Uri page = locator.createBoardUri(data.boardName, data.pageNumber);
		HttpResponse response = new HttpRequest(page, data).setValidator(data.validator).perform();
		HttpValidator validator = response.getValidator();
		Document document = readDocument(response, page);
		ArrayList<Posts> threads = new ArrayList<>();
		for (Element entry : document.select(ENTRY)) {
			String number = entry.id().replaceFirst("^post_", "");
			if (!number.matches(NUMBER)) continue;
			Post post = readEntry(entry, number);
			Element info = entry.selectFirst(".b-blog-entry_b-info_b-link");
			Matcher count = REPLY_COUNT.matcher(info != null ? info.text() : "");
			threads.add(new Posts(post).addPostsCount(count.find() ? 1 + Integer.parseInt(count.group(1)) : 1));
		}
		if (threads.isEmpty()) throw new InvalidResponseException();
		return new ReadThreadsResult(threads).setValidator(validator);
	}

	@Override
	public ReadPostsResult onReadPosts(ReadPostsData data) throws HttpException, InvalidResponseException {
		Uri page = locator().createThreadUri(data.boardName, data.threadNumber);
		HttpResponse response = new HttpRequest(page, data).setValidator(data.validator).perform();
		HttpValidator validator = response.getValidator();
		Document document = readDocument(response, page);
		Element entry = document.getElementById("post_" + data.threadNumber);
		if (entry == null || !entry.hasClass("b-blog-entry")) throw new InvalidResponseException();
		ArrayList<Post> posts = new ArrayList<>();
		posts.add(readEntry(entry, data.threadNumber).setClosed(document.getElementById("comment_form") == null));
		for (Element comment : document.select(".l-comments-wrap " + COMMENT)) {
			String number = comment.id().replaceFirst("^comment_", "");
			if (!number.matches(NUMBER)) continue;
			Element body = comment.selectFirst(".b-comment_b-body");
			if (body == null) throw new InvalidResponseException();
			Element info = comment.selectFirst(".b-comment_b-info");
			Post post = readContent(body).setPostNumber(number).setThreadNumber(data.threadNumber)
					.setParentPostNumber(data.threadNumber).setTimestamp(parseDate(info));
			posts.add(post);
		}
		return new ReadPostsResult(posts).setValidator(validator).setFullThread(true);
	}

	private Post readEntry(Element entry, String number) throws InvalidResponseException {
		Element body = entry.selectFirst(".b-blog-entry_b-body");
		Element header = entry.selectFirst(".b-blog-entry_b-header");
		if (body == null || header == null) throw new InvalidResponseException();
		OnechanChanLocator locator = locator();
		Element title = null;
		for (Element link : header.select("a[href]")) {
			Uri target = Uri.parse(link.absUrl("href"));
			if (locator.isThreadUri(target) && number.equals(locator.getThreadNumber(target))) {
				title = link;
				break;
			}
		}
		String external = null;
		if (title == null) {
			title = header.selectFirst("a.m-external[href]");
			if (title != null) {
				Uri target = Uri.parse(title.absUrl("href"));
				if (target.getHost() != null && locator.isWebScheme(target)) external = target.toString();
			}
		}
		if (title == null) throw new InvalidResponseException();
		if (external != null) {
			body = body.clone();
			body.prependElement("p").appendElement("a").attr("href", external).text(title.text());
		}
		Element info = entry.selectFirst(".b-blog-entry_b-info_b-control");
		return readContent(body).setPostNumber(number).setThreadNumber(number)
				.setSubject(title.text()).setTimestamp(parseDate(info))
				.setSticky(header.selectFirst(".b-blog-entry_b-header_b-pinned-icon") != null);
	}

	private Post readContent(Element body) {
		Post post = new Post();
		Element comment = body.clone();
		comment.select("script, style, iframe, form").remove();
		List<FileAttachment> files = new ArrayList<>();
		for (Element image : comment.select("img[src]")) {
			Uri src = Uri.parse(image.absUrl("src"));
			if (src.getHost() == null || !"https".equals(src.getScheme()) || src.getPath() == null
					|| !MEDIA.matcher(src.getPath()).matches()) continue;
			Uri file = src;
			Element parent = image.parent();
			if (parent != null && parent.tagName().equals("a")) {
				Uri target = Uri.parse(parent.absUrl("href"));
				if (target.getHost() != null && "https".equals(target.getScheme()) && target.getPath() != null
						&& MEDIA.matcher(target.getPath()).matches()) file = target;
			}
			files.add(new FileAttachment().setFileUri(locator(), file).setThumbnailUri(locator(), src));
		}
		if (!files.isEmpty()) post.setAttachments(files);
		for (Element link : comment.select("a[href]")) {
			Uri uri = Uri.parse(link.absUrl("href"));
			if (locator().isThreadUri(uri)) {
				String fragment = locator().getPostNumber(uri);
				link.attr("href", uri.getEncodedPath() + (fragment != null ? "#" + fragment : ""));
			} else if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
				link.attr("href", uri.toString());
			}
		}
		return post.setComment(comment.html());
	}

	private static long parseDate(Element element) {
		if (element == null) return 0;
		Matcher date = DATE.matcher(element.text());
		if (!date.find()) return 0;
		for (int i = 0; i < MONTHS.length; i++) {
			if (!MONTHS[i].equalsIgnoreCase(date.group(2))) continue;
			try {
				ZoneId zone = ZoneId.of("Europe/Moscow");
				ZonedDateTime now = ZonedDateTime.now(zone);
				String year = date.group(3);
				LocalDateTime time = LocalDateTime.of(year == null ? now.getYear() : Integer.parseInt(year),
						Month.of(i + 1), Integer.parseInt(date.group(1)), Integer.parseInt(date.group(4)),
						Integer.parseInt(date.group(5)), Integer.parseInt(date.group(6)));
				if (year == null && time.isAfter(now.toLocalDateTime().plusDays(1))) time = time.minusYears(1);
				return time.atZone(zone).toInstant().toEpochMilli();
			} catch (DateTimeException e) {
				return 0;
			}
		}
		return 0;
	}

	@Override
	public ReadCaptchaResult onReadCaptcha(ReadCaptchaData data) throws HttpException, InvalidResponseException {
		if (data.mayShowLoadButton) return new ReadCaptchaResult(CaptchaState.NEED_LOAD, null);
		OnechanChanLocator locator = locator();
		boolean reply = data.threadNumber != null;
		Uri page = reply ? locator.createThreadUri(data.boardName, data.threadNumber) : locator.createAddUri();
		Uri endpoint = reply ? locator.createReplyUri(data.threadNumber) : locator.createAddUri();
		data.holder.setMayResolveFirewallBlock(false);
		CaptchaData captcha = new CaptchaData();
		if (!reply) {
			// The site's news form is only served once visiting the feed has created a session.
			Uri board = locator.createBoardUri(data.boardName, 0);
			HttpResponse response = new HttpRequest(board, data).perform();
			try {
				readSessionCookies(response, captcha);
			} finally {
				response.cleanupAndDisconnect();
			}
		}
		HttpResponse response = addSessionCookies(new HttpRequest(page, data), captcha)
				.setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		readSessionCookies(response, captcha);
		Element form = readDocument(response, page).getElementById(reply ? "comment_form" : "blog_form");
		if (form == null || !endpoint.equals(Uri.parse(form.absUrl("action")))) throw new InvalidResponseException();
		Element key = form.selectFirst("input[name=captcha_key]");
		if (key == null && form.selectFirst("input[name=captcha]") == null) {
			return new ReadCaptchaResult(CaptchaState.SKIP, captcha);
		}
		Element image = form.selectFirst("img#captchaimage[src], img[src*=/captcha/]");
		if (image == null || key == null || key.val().isEmpty()) throw new InvalidResponseException();
		Uri imageUri = Uri.parse(image.absUrl("src"));
		if (!"https".equals(imageUri.getScheme()) || !page.getHost().equals(imageUri.getHost())
				|| !"/captcha/".equals(imageUri.getPath()) || !key.val().equals(imageUri.getQueryParameter("key"))) {
			throw new InvalidResponseException();
		}
		HttpResponse imageResponse = addSessionCookies(new HttpRequest(imageUri, data), captcha)
				.addHeader("Referer", page.toString()).setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		readSessionCookies(imageResponse, captcha);
		Bitmap bitmap = imageResponse.readBitmap();
		if (bitmap == null || captcha.get(SESSION) == null) throw new InvalidResponseException();
		captcha.put(CaptchaData.CHALLENGE, key.val());
		return new ReadCaptchaResult(CaptchaState.CAPTCHA, captcha).setImage(bitmap)
				.setValidity(ChanConfiguration.Captcha.Validity.SHORT_LIFETIME);
	}

	private static void readSessionCookies(HttpResponse response, CaptchaData captcha) {
		String session = response.getCookieValue(SESSION_COOKIE);
		String key = response.getCookieValue(SITE_SESSION_COOKIE);
		if (session != null) captcha.put(SESSION, session);
		if (key != null) captcha.put(SITE_SESSION, key);
	}

	private static HttpRequest addSessionCookies(HttpRequest request, CaptchaData captcha) {
		return captcha != null ? request.addCookie(SESSION_COOKIE, captcha.get(SESSION))
				.addCookie(SITE_SESSION_COOKIE, captcha.get(SITE_SESSION)) : request;
	}

	@Override
	public SendPostResult onSendPost(SendPostData data) throws HttpException, ApiException,
			InvalidResponseException {
		OnechanChanLocator locator = locator();
		boolean reply = data.threadNumber != null;
		Uri endpoint = reply ? locator.createReplyUri(data.threadNumber) : locator.createAddUri();
		Uri page = reply ? locator.createThreadUri(data.boardName, data.threadNumber) : endpoint;
		UrlEncodedEntity entity = new UrlEncodedEntity("homeboard", "anonymous", "email", "",
				"text", StringUtils.emptyIfNull(data.comment));
		if (reply) {
			entity.add("post_id", data.threadNumber);
		} else {
			if (StringUtils.isEmpty(data.subject)) throw new ApiException(ApiException.SEND_ERROR_EMPTY_SUBJECT);
			entity.add("title", data.subject);
			entity.add("link", "");
			entity.add("category", "");
			entity.add("text_full", "");
		}
		if (data.captchaData != null) {
			entity.add("captcha_key", data.captchaData.get(CaptchaData.CHALLENGE));
			entity.add("captcha", data.captchaData.get(CaptchaData.INPUT));
		}
		data.getHolder().setMayResolveFirewallBlock(false);
		HttpResponse response = addSessionCookies(new HttpRequest(endpoint, data), data.captchaData)
				.addHeader("Referer", page.toString()).setPostMethod(entity).setSuccessOnly(false)
				.setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		int status = response.getResponseCode();
		if (status >= 400) {
			try {
				response.checkResponseCode();
			} finally {
				response.cleanupAndDisconnect();
			}
			throw new HttpException(status, null);
		}
		Uri target = response.getRedirectedUri();
		if (target != null) {
			response.cleanupAndDisconnect();
			String thread = locator.getThreadNumber(target);
			String post = locator.getPostNumber(target);
			if (!"https".equals(target.getScheme()) || !page.getHost().equals(target.getHost())
					|| !locator.isThreadUri(target) || (reply && !data.threadNumber.equals(thread))) {
				throw new InvalidResponseException();
			}
			if (!reply || post != null) return new SendPostResult(thread, reply ? post : thread);
			if (!"comment_form".equals(target.getFragment())) throw new InvalidResponseException();
			response = addSessionCookies(new HttpRequest(page, data), data.captchaData)
					.setRedirectHandler(HttpRequest.RedirectHandler.NONE).perform();
		}
		Document document = readDocument(response, page);
		Element error = document.getElementById(reply ? "comment_form_error" : "blog_form_error");
		if (document.selectFirst("input[name=captcha].g-input-error") != null) {
			throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
		}
		if (error != null && !error.text().isEmpty()) {
			String message = error.text();
			if (message.toLowerCase(Locale.ROOT).contains("капч")) throw new ApiException(ApiException.SEND_ERROR_CAPTCHA);
			throw new ApiException(message);
		}
		throw new InvalidResponseException();
	}
}
