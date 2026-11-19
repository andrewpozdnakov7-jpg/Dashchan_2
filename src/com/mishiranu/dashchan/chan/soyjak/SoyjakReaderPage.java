package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import chan.content.model.Board;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** A local presentation of the document the reader opened in the foreground. */
public final class SoyjakReaderPage {
	private static final Pattern PATH = Pattern.compile("/([a-z0-9][a-z0-9_-]*)/(?:catalog\\.(json|html)|(?:thread|res)/([1-9][0-9]*)\\.html)");
	private static final Pattern BOARD_PAGE = Pattern.compile(
			"/([a-z0-9][a-z0-9_-]*)/(?:index\\.html|([0-9]+)\\.html)?");
	private static final Pattern POST_ID = Pattern.compile("(?:op_|reply_|post_|p)?([1-9][0-9]*)");
	private static final Pattern REPLY_COUNT = Pattern.compile("\\bR:\\s*([0-9]{1,9})\\b");
	public final String board;
	public final String thread;
	public final boolean json;
	private final String pageNumber;

	private SoyjakReaderPage(String board, String thread, boolean json, String pageNumber) {
		this.board = board;
		this.thread = thread;
		this.json = json;
		this.pageNumber = pageNumber;
	}

	public static boolean isSite(Uri uri) {
		return uri != null && "https".equalsIgnoreCase(uri.getScheme())
				&& ("soyjak.st".equalsIgnoreCase(uri.getEncodedAuthority())
				|| "www.soyjak.st".equalsIgnoreCase(uri.getEncodedAuthority()));
	}

	public static SoyjakReaderPage from(Uri uri) {
		if (!isSite(uri) || uri.getEncodedQuery() != null || uri.getEncodedPath() == null) return null;
		String raw = uri.getEncodedPath();
		if (raw.isEmpty() || "/".equals(raw) || "/index.html".equals(raw)) return new SoyjakReaderPage(null, null, false, null);
		Matcher boardPage = BOARD_PAGE.matcher(raw);
		if (boardPage.matches()) {
			String pageNumber = boardPage.group(2);
			return new SoyjakReaderPage(boardPage.group(1), null, false, pageNumber != null ? pageNumber : "0");
		}
		Matcher path = PATH.matcher(raw);
		if (!path.matches() || path.group(3) != null && PostNumber.parseNullable(path.group(3)) == null) return null;
		return new SoyjakReaderPage(path.group(1), path.group(3), "json".equals(path.group(2)), null);
	}

	public static boolean sameReaderTarget(String first, String second) {
		if (first == null || second == null) return false;
		if (first.equals(second)) return true;
		SoyjakReaderPage a = from(Uri.parse(first));
		SoyjakReaderPage b = from(Uri.parse(second));
		return a != null && b != null && java.util.Objects.equals(a.board, b.board)
				&& java.util.Objects.equals(a.thread, b.thread) && a.json == b.json
				&& java.util.Objects.equals(a.pageNumber, b.pageNumber);
	}

	public List<Board> readBoards(String document, Uri uri, SoyjakChanLocator locator) throws JSONException {
		List<Board> boards = SoyjakChanPerformer.parseBoards(document, uri, locator);
		if (boards.isEmpty()) throw new JSONException("Board table unavailable");
		return boards;
	}

	public List<Posts> read(String document, Uri uri, SoyjakChanLocator locator) throws JSONException {
		if (json) return SoyjakChanPerformer.parseCatalogDocument(board, document);
		Document html = Jsoup.parse(document, uri.toString());
		return thread == null ? readCatalog(html, locator) : readThread(html, locator);
	}

	private List<Posts> readCatalog(Document html, SoyjakChanLocator locator) throws JSONException {
		LinkedHashMap<String, Posts> rows = new LinkedHashMap<>();
		for (Element link : html.select("a[href]")) {
			Uri target = Uri.parse(link.absUrl("href"));
			SoyjakReaderPage page = from(target);
			if (page == null || !board.equals(page.board) || page.thread == null || target.getFragment() != null
					|| rows.containsKey(page.thread)) continue;
			Element card = link.closest(".catalog-thread, .thread, .mix");
			if (card == null || card == html.body()) continue;
			if (pageNumber != null && card.selectFirst(".post.op") == null) continue;
			Element image = link.selectFirst("img[src]");
			if (image == null) image = card.selectFirst("img[src]");
			Element subject = card.selectFirst(".subject");
			Element comment = card.selectFirst(".teaser, .post.op .body, .body, .comment");
			Post post = new Post().setPostNumber(page.thread).setThreadNumber(page.thread)
					.setSubject(subject != null ? subject.text() : link.text())
					.setComment(comment != null ? comment.html() : null);
			// Catalog thumbnails often link to the thread. Keep the thumbnail distinct from an original.
			Uri thumb = mediaUri(image, "src", "thumb");
			Element original = image != null ? image.closest("a[href]") : null;
			Uri file = mediaUri(original, "href", "src");
			if (file != null || thumb != null) {
				post.setAttachments(new FileAttachment().setFileUri(locator, file)
						.setOriginalName(file != null ? file.getLastPathSegment() : null)
						.setThumbnailUri(locator, thumb).setSpoiler(isSpoiler(image)));
			}
			Posts row = new Posts(post);
			Element replies = card.selectFirst(".replies");
			if (replies != null) {
				Matcher count = REPLY_COUNT.matcher(replies.text());
				if (count.find()) row.addPostsCount(Integer.parseInt(count.group(1)) + 1);
			}
			rows.put(page.thread, row);
		}
		if (rows.isEmpty()) throw new JSONException("No recognizable catalog entries");
		return new ArrayList<>(rows.values());
	}

	private List<Posts> readThread(Document html, SoyjakChanLocator locator) throws JSONException {
		List<Posts> rows = new ArrayList<>();
		boolean originalSeen = false;
		HashSet<String> seen = new HashSet<>();
		for (Element element : html.select(".post.op, .post.reply")) {
			boolean original = element.hasClass("op");
			Element intro = element.selectFirst(".intro[id]");
			Matcher number = POST_ID.matcher(element.id());
			if (!number.matches() && intro != null) number = POST_ID.matcher(intro.id());
			if (!number.matches() || PostNumber.parseNullable(number.group(1)) == null) throw new JSONException("Unknown post identifier");
			if (original && !thread.equals(number.group(1))) continue;
			Element owner = element.closest("div.thread");
			if (owner != null && !owner.id().isEmpty() && owner.id().matches("thread_?[0-9]+")
					&& !owner.id().replaceFirst("^thread_?", "").equals(thread)) continue;
			Element body = element.selectFirst(".body, blockquote");
			if (body == null || (!original && !originalSeen)) throw new JSONException("Incomplete thread markup");
			if (!seen.add(number.group(1))) throw new JSONException("Duplicate post identifier");
			originalSeen |= original;
			Element subject = element.selectFirst(".subject");
			Element name = element.selectFirst(".name");
			Element time = element.selectFirst("time[datetime]");
			Post post = new Post().setPostNumber(number.group(1)).setThreadNumber(thread)
					.setParentPostNumber(original ? null : thread)
					.setSubject(subject != null ? subject.text() : null)
					.setName(name != null ? name.text() : null).setComment(body.html());
			if (time != null) {
				try {
					post.setTimestamp(OffsetDateTime.parse(time.attr("datetime")).toInstant().toEpochMilli());
				} catch (DateTimeParseException e) {
					// Some templates omit an ISO timestamp.
				}
			}
			LinkedHashMap<Uri, FileAttachment> attachments = new LinkedHashMap<>();
			collectFiles(element, locator, attachments);
			if (original) {
				for (Element prev = element.previousElementSibling(); prev != null
						&& (prev.hasClass("files") || prev.hasClass("file")); prev = prev.previousElementSibling()) {
					collectFiles(prev, locator, attachments);
				}
			}
			post.setAttachments(attachments.values());
			rows.add(new Posts(post));
		}
		if (!originalSeen) throw new JSONException("Thread not present");
		return rows;
	}

	private void collectFiles(Element container, SoyjakChanLocator locator, LinkedHashMap<Uri, FileAttachment> files) {
		for (Element link : container.select("a[href]")) {
			if (link.closest(".body, blockquote") != null) continue;
			Uri file = mediaUri(link, "href", "src");
			if (file == null) continue;
			FileAttachment attachment = files.get(file);
			if (attachment == null) {
				attachment = new FileAttachment().setFileUri(locator, file).setOriginalName(file.getLastPathSegment());
				files.put(file, attachment);
			}
			Element image = link.selectFirst("img[src]");
			Uri thumb = mediaUri(image, "src", "thumb");
			if (thumb != null) attachment.setThumbnailUri(locator, thumb);
			if (isSpoiler(image)) attachment.setSpoiler(true);
		}
	}

	private static boolean isSpoiler(Element image) {
		return image != null && (image.hasClass("spoiler") || "spoiler".equalsIgnoreCase(image.attr("alt")));
	}

	private Uri mediaUri(Element element, String attr, String folder) {
		if (element == null) return null;
		Uri uri = Uri.parse(element.absUrl(attr));
		if (!isSite(uri) || uri.getEncodedQuery() != null || uri.getFragment() != null) return null;
		List<String> parts = uri.getPathSegments();
		if (parts.size() != 3 || !board.equals(parts.get(0)) || !folder.equals(parts.get(1))) return null;
		String file = parts.get(2);
		return file.matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+") ? uri : null;
	}
}
