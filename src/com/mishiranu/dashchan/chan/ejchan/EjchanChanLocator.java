package com.mishiranu.dashchan.chan.ejchan;

import android.net.Uri;
import chan.content.ChanLocator;
import java.util.List;
import java.util.regex.Pattern;

public class EjchanChanLocator extends ChanLocator {
	private static final Pattern BOARD_PATH = Pattern.compile("/[-\\w]+(?:/(?:(?:catalog|index|\\d+)\\.html)?)?");
	private static final Pattern THREAD_PATH = Pattern.compile("/[-\\w]+/res/(\\d+)(?:\\.(?:html|json))?");
	private static final Pattern ATTACHMENT_PATH = Pattern.compile("/[-\\w]+/(?:src|thumb)/[^/]+");

	public EjchanChanLocator() {
		// .site is the default for users in Russia; .net remains selectable for the rest of the world.
		addChanHost("ejchan.site");
		addChanHost("ejchan.net");
		setAutomaticDomainHosts("ejchan.site", "ejchan.net");
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
		return isChanHostOrRelative(uri) && isPathMatches(uri, ATTACHMENT_PATH);
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
		String fragment = uri != null ? uri.getFragment() : null;
		return fragment != null && fragment.startsWith("q") ? fragment.substring(1) : fragment;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		return pageNumber > 0 ? buildPath(boardName, (pageNumber + 1) + ".html")
				: buildPath(boardName, "index.html");
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(boardName, "res", threadNumber + ".html");
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon().fragment(postNumber).build();
	}

	public Uri createBoardsApiUri() {
		return buildPath("boards.json");
	}

	public Uri createThreadsApiUri(String boardName, int pageNumber, boolean catalog) {
		return buildPath(boardName, (catalog ? "catalog" : Integer.toString(pageNumber)) + ".json");
	}

	public Uri createThreadApiUri(String boardName, String threadNumber) {
		return buildPath(boardName, "res", threadNumber + ".json");
	}

	public Uri createFileUri(String boardName, String tim, String extension) {
		return buildPath(boardName, "src", tim + extension);
	}

	public Uri createThumbnailUri(String boardName, String thumbnail) {
		return buildPath(boardName, "thumb", thumbnail);
	}

	public Uri createCaptchaUri() {
		return buildQuery("inc/captcha/entrypoint.php", "mode", "get", "extra",
				"abcdefghijklmnopqrstuvwxyz");
	}

	public Uri createEpassUri(String code) {
		return buildQuery("inc/epass/check.php", "mode", "verify", "code", code);
	}

	public Uri createPostEndpointUri() {
		return buildPath("post.php");
	}
}
