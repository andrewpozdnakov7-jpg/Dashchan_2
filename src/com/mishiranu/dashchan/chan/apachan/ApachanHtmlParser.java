package com.mishiranu.dashchan.chan.apachan;

import android.net.Uri;
import chan.content.model.Attachment;
import chan.content.model.Board;
import chan.content.model.BoardCategory;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.util.StringUtils;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class ApachanHtmlParser {
	private static final Pattern NUMBER = Pattern.compile("\\d+");
	private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\s+([\\p{L}]+),\\s*(\\d{4})\\s+"
			+ "(\\d{1,2}):(\\d{2})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern FILE_SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(B|KB|MB)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern REPLIES = Pattern.compile("(?:Ответов|Replies)\\s*:\\s*(\\d+)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern DIMENSIONS = Pattern.compile("(\\d+)px\\s*[x×]\\s*(\\d+)px",
			Pattern.CASE_INSENSITIVE);
	private static final Map<String, Integer> MONTHS = createMonths();

	private ApachanHtmlParser() {}

	public static final class EditForm {
		public final String subject;
		public final String comment;
		public final boolean originalPost;
		public final boolean hasAttachment;
		public final boolean showOriginalPoster;
		public final int commentLimit;

		private EditForm(String subject, String comment, boolean originalPost, boolean hasAttachment,
				boolean showOriginalPoster, int commentLimit) {
			this.subject = subject;
			this.comment = comment;
			this.originalPost = originalPost;
			this.hasAttachment = hasAttachment;
			this.showOriginalPoster = showOriginalPoster;
			this.commentLimit = commentLimit;
		}
	}

	public static EditForm parseEditForm(String html) {
		Document document = Jsoup.parse(html);
		Element form = document.selectFirst("form[action$=edit_post.php], form[action$=edit_thread.php]");
		if (form == null) return null;
		Element id = form.selectFirst("input[name=id]");
		Element comment = form.selectFirst("textarea[name=text]");
		if (id == null || comment == null || !NUMBER.matcher(id.val()).matches()) return null;
		Element subject = form.selectFirst("input[name=title]");
		Element commentLimit = form.selectFirst("select[name=comment_limit] option[selected], "
				+ "input[name=comment_limit]");
		int parsedCommentLimit = 10;
		if (commentLimit != null) {
			try {
				parsedCommentLimit = Integer.parseInt(commentLimit.val());
			} catch (NumberFormatException ignored) {}
		}
		String action = form.attr("action");
		return new EditForm(subject != null ? subject.val() : "", comment.val(), action.endsWith("edit_thread.php"),
				form.selectFirst("input[name=delete_img]") != null,
				form.selectFirst("input[name=show_op][checked]") != null, parsedCommentLimit);
	}

	public static BoardCategory parseBoards(String html) {
		Document document = Jsoup.parse(html);
		LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
		for (Element link : document.select("#header nav ul a[href]")) {
			Uri uri = Uri.parse(link.attr("href"));
			String path = uri.getPath();
			if (!"01".equals(uri.getQueryParameter("v")) || path == null || !path.endsWith(".php")) continue;
			String segment = path.substring(path.lastIndexOf('/') + 1);
			String boardName = segment.substring(0, segment.length() - 4);
			if (!boardName.matches("[A-Za-z0-9_-]+")) continue;
			String title = StringUtils.nullIfEmpty(link.text().trim());
			if (title == null) continue;
			String description = StringUtils.nullIfEmpty(link.attr("title").trim());
			boards.putIfAbsent(boardName, new Board(boardName, title, description));
		}
		return boards.isEmpty() ? null : new BoardCategory(null, new ArrayList<>(boards.values()));
	}

	public static ParsedThreads parseThreads(String html, ApachanChanLocator locator) {
		Document document = Jsoup.parse(html);
		ArrayList<Posts> threads = new ArrayList<>();
		for (Element element : document.select("div.post[id^=c]")) {
			Post post = parsePost(element, locator, null);
			if (post == null) continue;
			int replies = parseRepliesCount(element, post.getPostNumber());
			threads.add(new Posts(post).addPostsCount(replies + 1));
		}
		boolean validBoardPage = document.selectFirst("form[action$=new_thread.php] input[name=sec]") != null;
		return new ParsedThreads(threads, parsePagesCount(document), validBoardPage);
	}

	public static ParsedPosts parsePosts(String html, ApachanChanLocator locator, String threadNumber) {
		Document document = Jsoup.parse(html);
		ArrayList<Post> posts = new ArrayList<>();
		for (Element element : document.select("div.post[id^=c], div.comment[id^=c]")) {
			Post post = parsePost(element, locator, threadNumber);
			if (post != null) posts.add(post);
		}
		int pagesCount = parsePagesCount(document);
		int postsCount = pagesCount == 1 ? posts.size() : parsePostsCount(document);
		return new ParsedPosts(posts, pagesCount, postsCount);
	}

	public static String parseSectionId(String html) {
		Element input = Jsoup.parse(html).selectFirst("form[action$=new_thread.php] input[name=sec]");
		return input != null ? StringUtils.nullIfEmpty(input.attr("value").trim()) : null;
	}

	public static String parseError(String html) {
		Document document = Jsoup.parse(html);
		Element heading = document.selectFirst("h1, h2");
		if (heading != null && !heading.text().trim().isEmpty()) return heading.text().trim();
		String title = document.title().trim();
		if (!title.isEmpty()) return title;
		String text = document.body() != null ? document.body().text().trim() : null;
		return StringUtils.nullIfEmpty(text);
	}

	private static Post parsePost(Element element, ApachanChanLocator locator, String threadNumber) {
		String id = element.id();
		if (id.length() < 2 || id.charAt(0) != 'c' || !NUMBER.matcher(id.substring(1)).matches()) return null;
		String postNumber = id.substring(1);
		Post post = new Post().setPostNumber(postNumber);
		if (threadNumber != null && !threadNumber.equals(postNumber)) post.setParentPostNumber(threadNumber);

		Element subject = directChild(element, "h2");
		if (subject != null) post.setSubject(StringUtils.nullIfEmpty(subject.text().trim()));
		ArrayList<Uri> videoUris = new ArrayList<>();
		Element comment = directChild(element, "p");
		if (comment != null) {
			Element clone = comment.clone();
			clone.select("div.trash").remove();
			for (Element video : clone.select("video")) {
				String source = video.attr("src");
				if (StringUtils.isEmpty(source)) {
					Element sourceElement = video.selectFirst("source[src]");
					if (sourceElement != null) source = sourceElement.attr("src");
				}
				Uri videoUri = normalizeUri(locator, source);
				if (videoUri != null) videoUris.add(videoUri);
				video.remove();
			}
			for (Element link : clone.select("a[href]")) {
				if ("\u0414\u0430\u043b\u0435\u0435".equalsIgnoreCase(link.text().trim())) {
					link.remove();
				} else {
					normalizePostLink(link, locator, threadNumber);
				}
			}
			post.setComment(StringUtils.nullIfEmpty(clone.html().trim()));
		}

		ArrayList<Attachment> attachments = new ArrayList<>();
		for (Element link : directChildren(element, "a", "text_image")) {
			Uri fileUri = normalizeUri(locator, link.attr("href"));
			if (fileUri == null) continue;
			FileAttachment attachment = new FileAttachment().setFileUri(locator, fileUri);
			String fileName = fileUri.getLastPathSegment();
			if (fileName != null) attachment.setOriginalName(fileName.replace("_big.", "."));
			Element image = link.selectFirst("img[src]");
			if (image != null) {
				Uri thumbnailUri = normalizeUri(locator, image.attr("src"));
				if (thumbnailUri != null) attachment.setThumbnailUri(locator, thumbnailUri);
				parseAttachmentMetadata(attachment, image.attr("title"));
			}
			attachments.add(attachment);
		}
		for (Uri videoUri : videoUris) {
			FileAttachment attachment = new FileAttachment().setFileUri(locator, videoUri);
			String fileName = videoUri.getLastPathSegment();
			if (!StringUtils.isEmpty(fileName)) attachment.setOriginalName(fileName);
			attachments.add(attachment);
		}
		if (!attachments.isEmpty()) post.setAttachments(attachments);

		Element info = directChild(element, "span", "info");
		if (info != null) post.setTimestamp(parseTimestamp(info.text()));
		return post;
	}

	private static void normalizePostLink(Element link, ApachanChanLocator locator, String threadNumber) {
		if (StringUtils.isEmpty(threadNumber)) return;
		Uri uri = Uri.parse(link.attr("href"));
		String fragment = uri.getFragment();
		if (StringUtils.isEmpty(fragment) || fragment.length() < 2 || fragment.charAt(0) != 'c'
				|| !NUMBER.matcher(fragment.substring(1)).matches()
				|| !StringUtils.isEmpty(uri.getQueryParameter("id"))) return;
		String path = uri.getPath();
		if (!StringUtils.isEmpty(path) && !path.endsWith("post.php")) return;
		link.attr("href", locator.createPostUri(ApachanChanLocator.DEFAULT_BOARD_NAME,
				threadNumber, fragment.substring(1)).toString());
	}

	private static Element directChild(Element parent, String tagName) {
		for (Element child : parent.children()) {
			if (tagName.equals(child.tagName())) return child;
		}
		return null;
	}

	private static Element directChild(Element parent, String tagName, String className) {
		for (Element child : parent.children()) {
			if (tagName.equals(child.tagName()) && child.hasClass(className)) return child;
		}
		return null;
	}

	private static List<Element> directChildren(Element parent, String tagName, String className) {
		ArrayList<Element> result = new ArrayList<>();
		for (Element child : parent.children()) {
			if (tagName.equals(child.tagName()) && child.hasClass(className)) result.add(child);
		}
		return result;
	}

	private static Uri normalizeUri(ApachanChanLocator locator, String uriString) {
		if (StringUtils.isEmpty(uriString)) return null;
		uriString = uriString.trim();
		if (uriString.startsWith("//")) uriString = "https:" + uriString;
		Uri uri = Uri.parse(uriString);
		if (StringUtils.isEmpty(uri.getScheme())) {
			String path = uriString.replaceFirst("^/+", "");
			return locator.buildPath(path);
		}
		String encodedPath = uri.getEncodedPath();
		if (!StringUtils.isEmpty(encodedPath)) {
			String normalizedPath = encodedPath.replaceFirst("^/+", "/");
			if (!encodedPath.equals(normalizedPath)) {
				uri = uri.buildUpon().encodedPath(normalizedPath).build();
			}
		}
		if ("http".equalsIgnoreCase(uri.getScheme())) uri = uri.buildUpon().scheme("https").build();
		return locator.convert(uri);
	}

	private static int parseRepliesCount(Element postElement, String postNumber) {
		Element info = directChild(postElement, "div", "info");
		if (info == null) info = directChild(postElement, "span", "info");
		if (info == null) return 0;
		for (Element link : info.select("a[href]")) {
			Uri uri = Uri.parse(link.attr("href"));
			if (!postNumber.equals(uri.getQueryParameter("id"))) continue;
			Matcher matcher = REPLIES.matcher(link.text());
			if (matcher.find()) {
				try {
					return Integer.parseInt(matcher.group(1));
				} catch (NumberFormatException ignored) {}
			}
			String fragment = uri.getFragment();
			if (fragment != null && NUMBER.matcher(fragment).matches()) {
				try {
					return Integer.parseInt(fragment);
				} catch (NumberFormatException ignored) {}
			}
		}
		return 0;
	}

	private static void parseAttachmentMetadata(FileAttachment attachment, String title) {
		if (StringUtils.isEmpty(title)) return;
		Matcher sizeMatcher = FILE_SIZE.matcher(title);
		if (sizeMatcher.find()) {
			double size = Double.parseDouble(sizeMatcher.group(1).replace(',', '.'));
			switch (sizeMatcher.group(2).toUpperCase(Locale.US)) {
				case "KB": size *= 1024d; break;
				case "MB": size *= 1024d * 1024d; break;
			}
			attachment.setSize((int) Math.min(Integer.MAX_VALUE, Math.round(size)));
		}
		Matcher dimensionsMatcher = DIMENSIONS.matcher(title);
		if (dimensionsMatcher.find()) {
			attachment.setWidth(Integer.parseInt(dimensionsMatcher.group(1)));
			attachment.setHeight(Integer.parseInt(dimensionsMatcher.group(2)));
		}
	}

	private static long parseTimestamp(String text) {
		Matcher matcher = DATE.matcher(text);
		if (!matcher.find()) return 0L;
		Integer month = MONTHS.get(matcher.group(2).toLowerCase(Locale.ROOT));
		if (month == null) return 0L;
		try {
			return LocalDateTime.of(Integer.parseInt(matcher.group(3)), month,
					Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(4)),
					Integer.parseInt(matcher.group(5))).toInstant(ZoneOffset.ofHours(3)).toEpochMilli();
		} catch (DateTimeException | NumberFormatException e) {
			return 0L;
		}
	}

	private static int parsePagesCount(Document document) {
		int pagesCount = 1;
		for (Element link : document.select("div.pages a[href]")) {
			String page = Uri.parse(link.attr("href")).getQueryParameter("page");
			if (page != null) {
				try {
					pagesCount = Math.max(pagesCount, Integer.parseInt(page));
				} catch (NumberFormatException ignored) {}
			}
		}
		return pagesCount;
	}

	private static int parsePostsCount(Document document) {
		Element replies = document.selectFirst("div.post[id^=c] span.info span#ans");
		return replies != null ? parseInteger(replies) + 1 : -1;
	}

	private static int parseInteger(Element element) {
		if (element == null) return 0;
		try {
			return Integer.parseInt(element.text().trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Map<String, Integer> createMonths() {
		LinkedHashMap<String, Integer> months = new LinkedHashMap<>();
		String[] names = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля",
				"августа", "сентября", "октября", "ноября", "декабря"};
		for (int i = 0; i < names.length; i++) months.put(names[i], i + 1);
		return months;
	}

	public static final class ParsedThreads {
		public final List<Posts> threads;
		public final int pagesCount;
		public final boolean validBoardPage;

		private ParsedThreads(List<Posts> threads, int pagesCount, boolean validBoardPage) {
			this.threads = threads;
			this.pagesCount = pagesCount;
			this.validBoardPage = validBoardPage;
		}
	}

	public static final class ParsedPosts {
		public final List<Post> posts;
		public final int pagesCount;
		public final int postsCount;

		private ParsedPosts(List<Post> posts, int pagesCount, int postsCount) {
			this.posts = posts;
			this.pagesCount = pagesCount;
			this.postsCount = postsCount;
		}
	}
}
