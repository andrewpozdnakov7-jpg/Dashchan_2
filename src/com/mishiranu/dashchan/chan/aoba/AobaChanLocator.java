package com.mishiranu.dashchan.chan.aoba;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.regex.Pattern;

public class AobaChanLocator extends ChanLocator {
	private static final Pattern BOARD = Pattern.compile("/([a-zA-Z0-9_]+)(?:/(?:(?:index|catalog|[0-9]+)\\.html)?)?");
	private static final Pattern THREAD = Pattern.compile("^/([a-zA-Z0-9_]+)/res/([1-9][0-9]*)\\.html$");
	private static final Pattern ATTACHMENT = Pattern.compile("/[a-zA-Z0-9_]+/(?:src|thumb)/[^/]+");
	private static final Pattern POST = Pattern.compile("^(?:q|i|reply)?([1-9][0-9]*)$");

	public AobaChanLocator() {
		addChanHost("aoba.me");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD);
	}

	@Override public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD);
	}

	@Override public boolean isAttachmentUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, ATTACHMENT);
	}

	@Override public String getBoardName(Uri uri) {
		if (isThreadUri(uri)) return getGroupValue(uri.getPath(), THREAD, 1);
		return isBoardUri(uri) ? getGroupValue(uri.getPath(), BOARD, 1) : null;
	}

	@Override public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD, 2) : null;
	}

	@Override public String getPostNumber(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		return fragment != null ? getGroupValue(fragment, POST, 1) : null;
	}

	@Override public Uri createBoardUri(String boardName, int pageNumber) {
		return pageNumber > 0 ? buildPath(boardName, pageNumber + ".html")
				: buildPath(boardName).buildUpon().appendEncodedPath("").build();
	}

	@Override public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(boardName, "res", threadNumber + ".html");
	}

	@Override public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}

	Uri createApiUri(String method, String params) {
		return buildQuery("api.php", "method", method, "id", "slooop", "params", params);
	}

	Uri createFileUri(String boardName, String folder, String name) {
		return buildPath(boardName, folder, name);
	}

	Uri createSendUri() {
		return buildPath("board.php");
	}
}
