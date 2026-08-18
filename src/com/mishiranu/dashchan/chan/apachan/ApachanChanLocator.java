package com.mishiranu.dashchan.chan.apachan;

import android.net.Uri;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import java.util.regex.Pattern;

public class ApachanChanLocator extends ChanLocator {
	public static final String DEFAULT_BOARD_NAME = "b";

	private static final Pattern BOARD_PATH = Pattern.compile("/[A-Za-z0-9_-]+\\.php");
	private static final Pattern THREAD_PATH = Pattern.compile("/post\\.php");
	private static final Pattern ATTACHMENT_PATH = Pattern.compile("/(?:img|randoms)/.+");

	public ApachanChanLocator() {
		addChanHost("apachan.space");
		addChanHost("apachan.icu");
		addConvertableChanHost("www.apachan.space");
		addConvertableChanHost("www.apachan.icu");
		setAutomaticDomainHosts("apachan.space", "apachan.icu");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, BOARD_PATH)
				&& !THREAD_PATH.matcher(uri.getPath()).matches();
	}

	@Override
	public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD_PATH)
				&& !StringUtils.isEmpty(uri.getQueryParameter("id"));
	}

	@Override
	public boolean isAttachmentUri(Uri uri) {
		return uri != null && (isChanHostOrRelative(uri) && isPathMatches(uri, ATTACHMENT_PATH)
				|| isImageExtension(uri.getPath()));
	}

	@Override
	public String getBoardName(Uri uri) {
		if (uri == null) return null;
		if (isThreadUri(uri)) return DEFAULT_BOARD_NAME;
		if (!isPathMatches(uri, BOARD_PATH)) return null;
		String segment = uri.getLastPathSegment();
		return segment != null ? segment.substring(0, segment.length() - 4) : null;
	}

	@Override
	public String getThreadNumber(Uri uri) {
		return uri != null && isPathMatches(uri, THREAD_PATH) ? uri.getQueryParameter("id") : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		return fragment != null && fragment.startsWith("c") ? fragment.substring(1) : fragment;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		String path = boardName + ".php";
		return pageNumber > 0 ? buildQuery(path, "page", Integer.toString(pageNumber + 1), "order", "0")
				: buildPath(path);
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildQuery("post.php", "id", threadNumber);
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment("c" + postNumber).build();
	}

	public Uri createThreadPageUri(String threadNumber, int pageNumber) {
		return pageNumber > 1 ? buildQuery("post.php", "id", threadNumber,
				"page", Integer.toString(pageNumber)) : createThreadUri(DEFAULT_BOARD_NAME, threadNumber);
	}

	public Uri createPostingUri(boolean newThread) {
		return buildPath("functions", newThread ? "new_thread.php" : "new_post.php");
	}

	public Uri createCookieBootstrapUri() {
		return buildPath("p.php");
	}
}
