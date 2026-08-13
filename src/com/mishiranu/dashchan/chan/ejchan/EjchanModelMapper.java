package com.mishiranu.dashchan.chan.ejchan;

import chan.content.model.Attachment;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

final class EjchanModelMapper {
	static final class Extra {
		int replies;
		int images;
	}

	public static Posts createThread(JsonSerial.Reader reader, EjchanChanLocator locator, String boardName,
			boolean catalog) throws IOException, ParseException {
		ArrayList<Post> posts = new ArrayList<>();
		int postsCount = 0;
		int filesCount = 0;
		if (catalog) {
			Extra extra = new Extra();
			Post originalPost = createPost(reader, locator, boardName, extra);
			posts.add(originalPost);
			postsCount = extra.replies + 1;
			filesCount = extra.images + originalPost.getAttachmentsCount();
		} else {
			reader.startObject();
			while (!reader.endStruct()) {
				if ("posts".equals(reader.nextName())) {
					Extra extra = new Extra();
					reader.startArray();
					while (!reader.endStruct()) {
						Post post = createPost(reader, locator, boardName, extra);
						posts.add(post);
						if (extra != null) {
							postsCount = extra.replies + 1;
							filesCount = extra.images + post.getAttachmentsCount();
							extra = null;
						}
					}
				} else {
					reader.skip();
				}
			}
		}
		return new Posts(posts).addPostsCount(postsCount).addFilesCount(filesCount);
	}

	public static Post createPost(JsonSerial.Reader reader, EjchanChanLocator locator, String boardName, Extra extra)
			throws IOException, ParseException {
		Post post = new Post();
		String tim = null;
		String filename = null;
		String extension = null;
		int size = -1;
		int width = 0;
		int height = 0;
		ArrayList<Attachment> attachments = new ArrayList<>();
		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "no": post.setPostNumber(reader.nextString()); break;
				case "resto": {
					String parent = reader.nextString();
					if (!"0".equals(parent)) post.setParentPostNumber(parent);
					break;
				}
				case "time": post.setTimestamp(reader.nextLong() * 1000L); break;
				case "sticky": post.setSticky(reader.nextBoolean()); break;
				case "closed":
				case "locked": post.setClosed(reader.nextBoolean()); break;
				case "archived": post.setArchived(reader.nextBoolean()); break;
				case "name": post.setName(clear(reader.nextString())); break;
				case "trip": post.setTripcode(reader.nextString()); break;
				case "email": {
					String email = reader.nextString();
					if ("sage".equals(email.toLowerCase(Locale.ROOT))) post.setSage(true);
					else post.setEmail(email);
					break;
				}
				case "id": post.setIdentifier(reader.nextString()); break;
				case "capcode": {
					String capcode = reader.nextString();
					if ("admin".equals(capcode) || "admin_highlight".equals(capcode)) post.setCapcode("Admin");
					else if ("mod".equals(capcode)) post.setCapcode("Mod");
					else if (!"none".equals(capcode)) post.setCapcode(capcode);
					break;
				}
				case "sub": post.setSubject(clear(reader.nextString())); break;
				case "com": post.setComment(parseComment(reader.nextString())); break;
				case "tim": tim = reader.nextString(); break;
				case "filename": filename = clear(reader.nextString()); break;
				case "ext": extension = reader.nextString(); break;
				case "fsize": size = reader.nextInt(); break;
				case "w": width = reader.nextInt(); break;
				case "h": height = reader.nextInt(); break;
				case "replies": {
					if (extra != null) extra.replies = reader.nextInt(); else reader.skip();
					break;
				}
				case "images": {
					if (extra != null) extra.images = reader.nextInt(); else reader.skip();
					break;
				}
				case "extra_files": {
					reader.startArray();
					while (!reader.endStruct()) {
						FileAttachment attachment = parseExtraFile(reader, locator, boardName);
						if (attachment != null) attachments.add(attachment);
					}
					break;
				}
				default: reader.skip(); break;
			}
		}
		if (tim != null && extension != null && size >= 0) {
			FileAttachment attachment = createFileAttachment(locator, boardName, tim, extension, filename,
					size, width, height);
			if (attachment != null) attachments.add(0, attachment);
		}
		if (!attachments.isEmpty()) post.setAttachments(attachments);
		if (CommonUtils.equals(post.getIdentifier(), post.getCapcode())) post.setIdentifier(null);
		return post;
	}

	private static FileAttachment parseExtraFile(JsonSerial.Reader reader, EjchanChanLocator locator,
			String boardName) throws IOException, ParseException {
		String tim = null;
		String filename = null;
		String extension = null;
		int size = -1;
		int width = 0;
		int height = 0;
		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "tim": tim = reader.nextString(); break;
				case "filename": filename = clear(reader.nextString()); break;
				case "ext": extension = reader.nextString(); break;
				case "fsize": size = reader.nextInt(); break;
				case "w": width = reader.nextInt(); break;
				case "h": height = reader.nextInt(); break;
				default: reader.skip(); break;
			}
		}
		return tim != null && extension != null && size >= 0 ? createFileAttachment(locator, boardName,
				tim, extension, filename, size, width, height) : null;
	}

	private static FileAttachment createFileAttachment(EjchanChanLocator locator, String boardName, String tim,
			String extension, String filename, int size, int width, int height) {
		if ("deleted".equals(extension)) return null;
		String thumbnail;
		switch (extension) {
			case ".mp4":
			case ".webm": thumbnail = tim + ".jpg"; break;
			case ".pdf": thumbnail = "pdf.png"; break;
			case ".webp":
			case ".gif":
			case ".jpeg":
			case ".jpg": thumbnail = tim + ".png"; break;
			default: thumbnail = tim + extension; break;
		}
		FileAttachment attachment = new FileAttachment();
		attachment.setSize(size);
		attachment.setWidth(width);
		attachment.setHeight(height);
		attachment.setFileUri(locator, locator.createFileUri(boardName, tim, extension));
		attachment.setThumbnailUri(locator, locator.createThumbnailUri(boardName, thumbnail));
		attachment.setOriginalName(filename);
		return attachment;
	}

	private static String clear(String value) {
		return StringUtils.clearHtml(StringUtils.emptyIfNull(value)).trim();
	}

	private static String parseComment(String comment) {
		return StringUtils.emptyIfNull(comment).replace("%23", "#");
	}
}
