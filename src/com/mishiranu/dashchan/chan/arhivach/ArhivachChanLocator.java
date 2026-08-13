package com.mishiranu.dashchan.chan.arhivach;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.regex.Pattern;

public class ArhivachChanLocator extends ChanLocator {
	private static final Pattern THREAD_PATH = Pattern.compile("/thread/(\\d+)/?");

	public ArhivachChanLocator() {
		addChanHost("arhivach.vc");
		addSpecialChanHost("arhivachqqqvwqcotafhk4ks2he56seuwcshpayrm5myeq45vlff44yd.onion");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override public boolean isBoardUri(Uri uri) { return false; }
	@Override public boolean isThreadUri(Uri uri) {
		return isChanHostOrRelative(uri) && isPathMatches(uri, THREAD_PATH);
	}
	@Override public boolean isAttachmentUri(Uri uri) {
		return uri != null && (isImageExtension(uri.getPath()) || isAudioExtension(uri.getPath())
				|| isVideoExtension(uri.getPath()));
	}
	@Override public String getBoardName(Uri uri) { return null; }
	@Override public String getThreadNumber(Uri uri) {
		return uri != null ? getGroupValue(uri.getPath(), THREAD_PATH, 1) : null;
	}
	@Override public String getPostNumber(Uri uri) { return uri != null ? uri.getFragment() : null; }
	@Override public Uri createBoardUri(String boardName, int pageNumber) {
		return pageNumber > 0 ? buildPath("index", Integer.toString(ArhivachChanPerformer.PAGE_SIZE * pageNumber))
				: buildPath();
	}
	@Override public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath("thread", threadNumber);
	}
	@Override public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}
	@Override public String createAttachmentForcedName(Uri fileUri) {
		if (isChanHostOrRelative(fileUri) && "a_cimg".equals(fileUri.getLastPathSegment())) {
			String query = fileUri.getQuery();
			if (query != null) return query.startsWith("h=") ? query.substring(2).replace("&", "")
					: query.replace("&", "");
		}
		return null;
	}
}
