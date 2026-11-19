package com.mishiranu.dashchan.chan.soyjak;

import android.net.Uri;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import java.util.List;
import java.util.regex.Pattern;

public class SoyjakChanLocator extends ChanLocator {
	private static final String BOARD_NAME = "[a-z0-9][a-z0-9_-]*";
	private static final Pattern BOARD_PATH = Pattern.compile("/" + BOARD_NAME
			+ "(?:/(?:index\\.html|catalog\\.(?:html|json)|[0-9]+\\.(?:html|json))?)?");
	private static final Pattern THREAD_PATH = Pattern.compile("/" + BOARD_NAME
			+ "/(?:thread|res)/([0-9]+)(?:\\.(?:html|json))?");
	private static final Pattern ATTACHMENT_PATH = Pattern.compile("/" + BOARD_NAME
			+ "/(?:src|thumb)/[^/]+");
	private static final Pattern POST_FRAGMENT = Pattern.compile("^[pq]?([0-9]+)$");

	public SoyjakChanLocator() {
		addChanHost("soyjak.st");
		addConvertableChanHost("www.soyjak.st");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD_PATH) && StringUtils.isEmpty(uri.getFragment());
	}

	@Override
	public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD_PATH);
	}

	@Override
	public boolean isAttachmentUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, ATTACHMENT_PATH);
	}

	@Override
	public String getBoardName(Uri uri) {
		List<String> segments = uri.getPathSegments();
		return segments.isEmpty() ? null : segments.get(0);
	}

	@Override
	public String getThreadNumber(Uri uri) {
		return isThreadUri(uri) ? getGroupValue(uri.getPath(), THREAD_PATH, 1) : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		return getGroupValue(uri.getFragment(), POST_FRAGMENT, 1);
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		if (pageNumber < 0) return buildPath(boardName, "catalog.html");
		return buildPath(boardName, pageNumber > 0 ? pageNumber + ".html" : "");
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(boardName, "thread", threadNumber + ".html");
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}

	Uri createThreadsApiUri(String boardName, int pageNumber) {
		return buildPath(boardName, (pageNumber < 0 ? "catalog" : Integer.toString(pageNumber)) + ".json");
	}

	Uri createThreadApiUri(String boardName, String threadNumber) {
		return buildPath(boardName, "thread", threadNumber + ".json");
	}
}
