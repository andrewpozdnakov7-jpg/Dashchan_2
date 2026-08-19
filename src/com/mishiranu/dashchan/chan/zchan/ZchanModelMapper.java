package com.mishiranu.dashchan.chan.zchan;

import android.net.Uri;
import chan.content.model.FileAttachment;
import chan.content.model.Post;
import chan.content.model.Posts;
import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.StringUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZchanModelMapper {
	private static final Pattern REPLY_LINK = Pattern.compile("href=\\\"[^\\\"]*\\\" class=\\\"post-reply-link\\\" "
			+ "data-thread=\\\"(\\d+)\\\" data-num=\\\"(\\d+)\\\"");

	private ZchanModelMapper() {}

	private static String normalizeComment(String boardName, String comment) {
		if (comment == null) return null;
		Matcher matcher = REPLY_LINK.matcher(comment);
		StringBuffer buffer = null;
		while (matcher.find()) {
			if (buffer == null) buffer = new StringBuffer(comment.length() + 16);
			String replacement = "href=\"/" + boardName + "/" + matcher.group(1) + "#" + matcher.group(2)
					+ "\" class=\"post-reply-link\" data-thread=\"" + matcher.group(1)
					+ "\" data-num=\"" + matcher.group(2) + "\"";
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
		}
		if (buffer == null) return comment;
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private static FileAttachment createAttachment(JsonSerial.Reader reader, ZchanChanLocator locator)
			throws IOException, ParseException {
		String path = null;
		String thumbnail = null;
		String originalName = null;
		long sizeKb = 0L;
		int width = 0;
		int height = 0;

		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "path":
					path = reader.nextString();
					break;
				case "thumb":
					thumbnail = reader.nextString();
					break;
				case "orig_name":
					originalName = reader.nextString();
					break;
				case "size":
					sizeKb = reader.nextLong();
					break;
				case "width":
					width = reader.nextInt();
					break;
				case "height":
					height = reader.nextInt();
					break;
				default:
					reader.skip();
					break;
			}
		}

		if (StringUtils.isEmpty(path)) return null;
		FileAttachment attachment = new FileAttachment()
				.setFileUri(locator, Uri.parse(path))
				.setOriginalName(originalName)
				.setWidth(width)
				.setHeight(height);
		if (!StringUtils.isEmpty(thumbnail)) attachment.setThumbnailUri(locator, Uri.parse(thumbnail));
		if (sizeKb > 0L) attachment.setSize((int) Math.min(Integer.MAX_VALUE, sizeKb * 1024L));
		return attachment;
	}

	public static Post createPost(JsonSerial.Reader reader, ZchanChanLocator locator, String boardName)
			throws IOException, ParseException {
		Post post = new Post();
		String postNumber = null;
		String threadNumber = null;
		ArrayList<FileAttachment> attachments = null;

		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "id":
					postNumber = reader.nextString();
					break;
				case "thread_id":
					threadNumber = reader.nextString();
					break;
				case "timestamp":
					post.setTimestamp(reader.nextLong());
					break;
				case "name":
					post.setName(StringUtils.clearHtml(reader.nextString()).trim());
					break;
				case "title":
					post.setSubject(StringUtils.clearHtml(reader.nextString()).trim());
					break;
				case "body":
					post.setComment(normalizeComment(boardName, reader.nextString()));
					break;
				case "is_sticked":
					post.setSticky(reader.nextBoolean());
					break;
				case "is_closed":
					post.setClosed(reader.nextBoolean());
					break;
				case "is_archived":
					post.setArchived(reader.nextBoolean());
					break;
				case "files":
					attachments = new ArrayList<>();
					reader.startArray();
					while (!reader.endStruct()) {
						FileAttachment attachment = createAttachment(reader, locator);
						if (attachment != null) attachments.add(attachment);
					}
					break;
				default:
					reader.skip();
					break;
			}
		}

		if (postNumber == null) throw new ParseException("Missing post id");
		post.setPostNumber(postNumber);
		if (threadNumber != null && !threadNumber.equals(postNumber)) post.setParentPostNumber(threadNumber);
		if (attachments != null) post.setAttachments(attachments);
		return post;
	}

	public static Posts createCatalogThread(JsonSerial.Reader reader, ZchanChanLocator locator, String boardName)
			throws IOException, ParseException {
		int postsCount = 0;
		int filesCount = 0;
		int uniquePosters = 0;
		Post post = new Post();
		String postNumber = null;
		ArrayList<FileAttachment> attachments = null;

		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "id":
					postNumber = reader.nextString();
					break;
				case "timestamp":
					post.setTimestamp(reader.nextLong());
					break;
				case "name":
					post.setName(StringUtils.clearHtml(reader.nextString()).trim());
					break;
				case "title":
					post.setSubject(StringUtils.clearHtml(reader.nextString()).trim());
					break;
				case "body":
					post.setComment(normalizeComment(boardName, reader.nextString()));
					break;
				case "is_sticked":
					post.setSticky(reader.nextBoolean());
					break;
				case "is_closed":
					post.setClosed(reader.nextBoolean());
					break;
				case "is_archived":
					post.setArchived(reader.nextBoolean());
					break;
				case "posts_count":
					postsCount = reader.nextInt();
					break;
				case "files_count":
					filesCount = reader.nextInt();
					break;
				case "unique_posters":
					uniquePosters = reader.nextInt();
					break;
				case "files":
					attachments = new ArrayList<>();
					reader.startArray();
					while (!reader.endStruct()) {
						FileAttachment attachment = createAttachment(reader, locator);
						if (attachment != null) attachments.add(attachment);
					}
					break;
				default:
					reader.skip();
					break;
			}
		}

		if (postNumber == null) throw new ParseException("Missing thread id");
		post.setPostNumber(postNumber);
		if (attachments != null) post.setAttachments(attachments);
		return new Posts(post).addPostsCount(postsCount + 1).addFilesCount(filesCount)
				.setUniquePosters(uniquePosters);
	}
}
