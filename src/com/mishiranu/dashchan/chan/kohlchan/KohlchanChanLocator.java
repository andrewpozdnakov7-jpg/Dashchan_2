package com.mishiranu.dashchan.chan.kohlchan;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KohlchanChanLocator extends ChanLocator {
	private static final Pattern BOARD = Pattern.compile("/([A-Za-z0-9_-]+)(?:/(?:(?:index|catalog|[0-9]+)\\.html)?)?");
	private static final Pattern THREAD = Pattern.compile("/([A-Za-z0-9_-]+)/res/([0-9]+)\\.html");

	public KohlchanChanLocator() {
		addChanHost("kohlchan.net");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD);
	}

	@Override
	public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD);
	}

	@Override
	public boolean isAttachmentUri(Uri uri) {
		return isChanHostOrRelative(uri) && uri.getPath() != null && uri.getPath().startsWith("/.media/");
	}

	@Override
	public String getBoardName(Uri uri) {
		if (uri == null || uri.getPath() == null) return null;
		Matcher matcher = THREAD.matcher(uri.getPath());
		if (matcher.matches()) return matcher.group(1);
		matcher = BOARD.matcher(uri.getPath());
		return matcher.matches() ? matcher.group(1) : null;
	}

	@Override
	public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD, 2) : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		return fragment != null && fragment.matches("[0-9]+") ? fragment : null;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		return pageNumber > 0 ? buildPath(boardName, (pageNumber + 1) + ".html") : buildPath(boardName, "");
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(boardName, "res", threadNumber + ".html");
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}
}
