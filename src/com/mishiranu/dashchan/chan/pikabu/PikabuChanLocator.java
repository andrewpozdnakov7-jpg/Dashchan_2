package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import java.util.List;
import java.util.regex.Pattern;

public class PikabuChanLocator extends ChanLocator {
	public static final String BOARD_HOT = "hot";
	public static final String BOARD_BEST = "best";
	public static final String BOARD_NEW = "new";

	private static final Pattern BOARD_PATH = Pattern.compile("/(?:best|new)?/?");
	private static final Pattern THREAD_PATH = Pattern.compile("/story/(?:[^/?#]*_)?(\\d+)/?");
	private static final Pattern COMMENT_FRAGMENT = Pattern.compile("comment_(\\d+)");

	public PikabuChanLocator() {
		addChanHost("pikabu.ru");
		addConvertableChanHost("www.pikabu.ru");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD_PATH);
	}

	@Override
	public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD_PATH);
	}

	@Override
	public boolean isAttachmentUri(Uri uri) {
		if (uri == null) return false;
		String path = uri.getPath();
		return isImageExtension(path) || isAudioExtension(path) || isVideoExtension(path);
	}

	@Override
	public String getBoardName(Uri uri) {
		if (uri == null || !isChanHostOrRelative(uri)) return null;
		List<String> segments = uri.getPathSegments();
		if (segments.isEmpty()) return BOARD_HOT;
		switch (segments.get(0)) {
			case BOARD_BEST:
				return BOARD_BEST;
			case BOARD_NEW:
				return BOARD_NEW;
			default:
				return isThreadUri(uri) ? BOARD_HOT : null;
		}
	}

	@Override
	public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD_PATH, 1) : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		if (uri == null) return null;
		String commentId = uri.isHierarchical() ? uri.getQueryParameter("cid") : null;
		if (!StringUtils.isEmpty(commentId) && commentId.matches("\\d+")) return commentId;
		String fragment = uri.getFragment();
		if (fragment == null) return null;
		String number = getGroupValue(fragment, COMMENT_FRAGMENT, 1);
		return number != null ? number : fragment.matches("\\d+") ? fragment : null;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		Uri uri;
		switch (boardName) {
			case BOARD_BEST:
				uri = buildPath(BOARD_BEST);
				break;
			case BOARD_NEW:
				uri = buildPath(BOARD_NEW);
				break;
			case BOARD_HOT:
			default:
				uri = buildPath();
				break;
		}
		return pageNumber > 0 ? uri.buildUpon().appendQueryParameter("page",
				Integer.toString(pageNumber + 1)).build() : uri;
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath("story", "_" + threadNumber);
	}

	public Uri createThreadCommentsUri(String threadNumber) {
		return buildPath("generate_xml_comm.php").buildUpon()
				.appendQueryParameter("id", threadNumber).build();
	}

	public Uri createCommentsActionsUri() {
		return buildPath("ajax", "comments_actions.php");
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon()
				.appendQueryParameter("cid", postNumber).fragment("comment_" + postNumber).build();
	}
}
