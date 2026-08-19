package com.mishiranu.dashchan.chan.zchan;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.List;
import java.util.regex.Pattern;

public class ZchanChanLocator extends ChanLocator {
	private static final Pattern BOARD_PATH = Pattern.compile("/[A-Za-z0-9_-]+/?");
	private static final Pattern THREAD_PATH = Pattern.compile("/[A-Za-z0-9_-]+/(\\d+)/?");

	public ZchanChanLocator() {
		addChanHost("zchan.app");
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
		String path = uri != null ? uri.getPath() : null;
		return isChanHostOrRelative(uri) && path != null && path.startsWith("/src/")
				&& (isImageExtension(path) || isAudioExtension(path) || isVideoExtension(path));
	}

	@Override
	public String getBoardName(Uri uri) {
		List<String> segments = uri != null ? uri.getPathSegments() : null;
		return segments != null && !segments.isEmpty() ? segments.get(0) : null;
	}

	@Override
	public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD_PATH, 1) : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		return uri != null ? uri.getFragment() : null;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		return buildPath(boardName);
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(boardName, threadNumber);
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}

	public Uri createBoardsApiUri() {
		return buildPath("api", "boards");
	}

	public Uri createThreadsApiUri(String boardName) {
		return buildPath("api", boardName);
	}

	public Uri createThreadApiUri(String boardName, String threadNumber, String startPostNumber) {
		Uri uri = buildPath("api", boardName, threadNumber);
		return startPostNumber != null
				? uri.buildUpon().appendQueryParameter("start_post_id", startPostNumber).build() : uri;
	}

	public Uri createPostingApiUri(String boardName, String threadNumber) {
		return threadNumber != null ? buildPath("api", boardName, threadNumber) : buildPath("api", boardName);
	}

	public Uri createRegistrationApiUri() {
		return new Uri.Builder().scheme("https").authority("api.zchan.app")
				.appendPath("v1").appendPath("ichan").build();
	}
}
