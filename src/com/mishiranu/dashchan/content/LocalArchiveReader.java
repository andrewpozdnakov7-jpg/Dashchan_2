package com.mishiranu.dashchan.content;

import android.net.Uri;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Parses archives produced by Slooop into the native post model. */
public final class LocalArchiveReader {
	private static final String LOCAL_BASE_URL = "https://local.archive/";

	private LocalArchiveReader() {}

	public static class Archive {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final LinkedHashMap<PostNumber, PostItem> postItems;

		private Archive(String chanName, String boardName, String threadNumber,
				LinkedHashMap<PostNumber, PostItem> postItems) {
			this.chanName = chanName;
			this.boardName = boardName;
			this.threadNumber = threadNumber;
			this.postItems = postItems;
		}
	}

	public static class PostSource {
		public final String number;
		public final Element header;
		public final ArrayList<Element> files;
		public final Element comment;

		private PostSource(String number, Element header, ArrayList<Element> files, Element comment) {
			this.number = number;
			this.header = header;
			this.files = files;
			this.comment = comment;
		}
	}

	public static Archive read(LocalArchiveManager.Item item) {
		try {
			String html = new String(LocalArchiveManager.readHtml(item), java.nio.charset.StandardCharsets.UTF_8);
			return parse(item, html, LocalArchiveManager.readManifest(item));
		} catch (java.io.IOException e) {
			return null;
		}
	}

	public static Archive parse(LocalArchiveManager.Item item, String html, JSONObject manifest) {
		try {
			Document document = Jsoup.parse(html, LOCAL_BASE_URL);
			Element postsRoot = document.getElementById("delform");
			ArrayList<PostSource> sources = collectPostSources(postsRoot);
			if (sources.isEmpty()) {
				return null;
			}
			String chanName = manifest != null ? manifest.optString("chan", null) : null;
			String boardName = manifest != null ? manifest.optString("board", null) : null;
			String threadNumber = manifest != null ? manifest.optString("thread", null) : null;
			Uri threadUri = postsRoot != null ? Uri.parse(postsRoot.attr("data-thread-uri")) : null;
			Chan chan = Chan.getPreferred(chanName, threadUri);
			if (chan.name == null) {
				return null;
			}
			chanName = chan.name;
			if (StringUtils.isEmpty(boardName) && threadUri != null) {
				boardName = chan.locator.safe(false).getBoardName(threadUri);
			}
			if (StringUtils.isEmpty(threadNumber) && threadUri != null) {
				threadNumber = chan.locator.safe(false).getThreadNumber(threadUri);
			}
			if (StringUtils.isEmpty(boardName) || StringUtils.isEmpty(threadNumber)) {
				return null;
			}
			PostNumber originalPostNumber = PostNumber.parseNullable(sources.get(0).number);
			if (originalPostNumber == null) {
				return null;
			}
			LinkedHashMap<PostNumber, PostItem> postItems = new LinkedHashMap<>();
			int ordinalIndex = 0;
			for (PostSource source : sources) {
				PostNumber number = PostNumber.parseNullable(source.number);
				if (number == null) {
					continue;
				}
				Post.Builder builder = new Post.Builder();
				builder.number = number;
				Element subject = source.header.selectFirst("[data-subject]");
				Element poster = source.header.selectFirst(".postername");
				Element trip = source.header.selectFirst(".postertrip");
				Element timestamp = source.header.selectFirst("[data-timestamp]");
				builder.subject = subject != null ? subject.text() : null;
				if (poster != null) {
					builder.name = poster.attr("data-name");
					builder.identifier = poster.attr("data-identifier");
					builder.email = poster.attr("data-email");
					builder.setDefaultName(poster.hasAttr("data-default-name"));
				}
				if (trip != null) {
					builder.tripcode = trip.attr("data-tripcode");
					builder.capcode = trip.attr("data-capcode");
					builder.setOriginalPoster(trip.hasAttr("data-op"));
				}
				builder.setSage(source.header.selectFirst("[data-sage]") != null);
				builder.timestamp = parseLong(timestamp != null ? timestamp.attr("data-timestamp") : null);
				builder.comment = source.comment.html();
				ArrayList<Post.Icon> icons = new ArrayList<>();
				for (Element iconElement : source.header.select("img[data-icon]")) {
					Uri iconUri = LocalArchiveManager.createResourceUri(item, iconElement.attr("src"));
					Post.Icon icon = Post.Icon.createExternal(iconUri, iconElement.attr("title"));
					if (icon != null) {
						icons.add(icon);
					}
				}
				builder.icons = icons;
				ArrayList<Post.Attachment> attachments = new ArrayList<>();
				for (Element file : source.files) {
					String filePath = file.attr("data-file");
					String thumbnailPath = file.attr("data-thumbnail");
					if (StringUtils.isEmpty(filePath) && StringUtils.isEmpty(thumbnailPath)) {
						continue;
					}
					Uri fileUri = !StringUtils.isEmpty(filePath)
							? LocalArchiveManager.createResourceUri(item, filePath) : null;
					Uri thumbnailUri = !StringUtils.isEmpty(thumbnailPath)
							? LocalArchiveManager.createResourceUri(item, thumbnailPath) : null;
					Post.Attachment.File attachment = Post.Attachment.File.createExternal(fileUri, thumbnailUri,
							file.attr("data-original-name"), parseInt(file.attr("data-size")),
							parseInt(file.attr("data-width")), parseInt(file.attr("data-height")), false);
					if (attachment != null) {
						attachments.add(attachment);
					}
				}
				builder.attachments = attachments;
				Element reflink = source.header.selectFirst(".reflink");
				boolean deleted = reflink != null && reflink.text().contains("DELETED");
				PostItem postItem = PostItem.createPost(builder.build(deleted), chan,
						boardName, threadNumber, originalPostNumber);
				postItem.setOrdinalIndex(deleted ? PostItem.ORDINAL_INDEX_DELETED : ordinalIndex++);
				postItems.put(number, postItem);
			}
			for (PostItem postItem : postItems.values()) {
				for (PostNumber reference : postItem.getReferencesTo()) {
					PostItem referenced = postItems.get(reference);
					if (referenced != null) {
						referenced.addReferenceFrom(postItem.getPostNumber());
					}
				}
			}
			return postItems.isEmpty() ? null
					: new Archive(chanName, boardName, threadNumber, postItems);
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static ArrayList<PostSource> collectPostSources(Element postsRoot) {
		ArrayList<PostSource> result = new ArrayList<>();
		if (postsRoot == null) {
			return result;
		}
		ArrayList<Element> markers = new ArrayList<>();
		for (Element child : postsRoot.children()) {
			if (child.hasAttr("data-number")) {
				markers.add(child);
			}
		}
		for (int i = 0; i < markers.size(); i++) {
			Element marker = markers.get(i);
			Element boundary = i + 1 < markers.size() ? markers.get(i + 1) : null;
			Element next = marker.nextElementSibling();
			Element scope = next != null && "table".equals(next.normalName())
					? next.selectFirst("td.reply") : null;
			Element header = null;
			Element comment = null;
			ArrayList<Element> files = new ArrayList<>();
			if (scope != null) {
				header = scope.selectFirst("div.replyheader");
				comment = scope.selectFirst("[data-comment]");
				files.addAll(scope.select("span[data-file]"));
			} else {
				for (Element sibling = next; sibling != null && sibling != boundary;
						sibling = sibling.nextElementSibling()) {
					if (header == null && "div".equals(sibling.normalName())
							&& sibling.selectFirst("a[name]") != null) {
						header = sibling;
					}
					if (comment == null && sibling.hasAttr("data-comment")) {
						comment = sibling;
					}
					if (sibling.hasAttr("data-file")) {
						files.add(sibling);
					}
					files.addAll(sibling.select("span[data-file]"));
				}
			}
			if (header != null && comment != null) {
				result.add(new PostSource(marker.attr("data-number"), header, files, comment));
			}
		}
		return result;
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}
}
