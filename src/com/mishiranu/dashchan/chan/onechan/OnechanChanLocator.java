package com.mishiranu.dashchan.chan.onechan;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.regex.Pattern;

public class OnechanChanLocator extends ChanLocator {
	private static final Pattern BOARD = Pattern.compile("/news(?:/all(?:/[0-9]+)?)?/?");
	private static final Pattern THREAD = Pattern.compile("^/news/res/([1-9][0-9]*)/?$");
	private static final Pattern POST = Pattern.compile("^(?:comment_)?([1-9][0-9]*)$");

	public OnechanChanLocator() {
		addChanHost("1chan.ink");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD);
	}

	@Override public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD);
	}

	@Override public String getBoardName(Uri uri) {
		return isBoardUri(uri) || isThreadUri(uri) ? OnechanChanConfiguration.BOARD : null;
	}

	@Override public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD, 1) : null;
	}

	@Override public String getPostNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getFragment(), POST, 1) : null;
	}

	@Override public Uri createBoardUri(String boardName, int pageNumber) {
		return pageNumber > 0 ? buildPath("news", "all", Integer.toString(pageNumber)).buildUpon()
				.appendEncodedPath("").build() : buildPath("news", "all").buildUpon().appendEncodedPath("").build();
	}

	@Override public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath("news", "res", threadNumber).buildUpon().appendEncodedPath("").build();
	}

	@Override public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}

	Uri createAddUri() {
		return buildPath("news", "add").buildUpon().appendEncodedPath("").build();
	}

	Uri createReplyUri(String threadNumber) {
		return buildPath("news", "res", threadNumber, "add_comment").buildUpon().appendEncodedPath("").build();
	}
}
