package com.mishiranu.dashchan.chan.d3ru;

import android.net.Uri;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class D3ChanLocator extends ChanLocator {
	public static final String BOARD_ALL = "all";
	private static final Pattern BOARD_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
	private static final Pattern THREAD_PATH = Pattern.compile("/(?:[^/?#]*-)?(\\d+)/?");
	private static final Pattern COMMENT_FRAGMENT = Pattern.compile("(?:comment[-_])?(\\d+)");

	@SuppressWarnings("unchecked")
	public static D3ChanLocator get(Object object) {
		return ChanLocator.get(object);
	}

	public D3ChanLocator() {
		addChanHost("d3.ru");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	private static boolean isD3Host(String host) {
		if (StringUtils.isEmpty(host)) return false;
		host = host.toLowerCase(Locale.US);
		if ("d3.ru".equals(host)) return true;
		if (!host.endsWith(".d3.ru")) return false;
		return BOARD_NAME.matcher(host.substring(0, host.length() - ".d3.ru".length())).matches();
	}

	public static boolean isBoardName(String boardName) {
		return BOARD_ALL.equals(boardName) || boardName != null && BOARD_NAME.matcher(boardName).matches();
	}

	@Override
	protected String getHostTransition(String chanHost, String requiredHost) {
		return isD3Host(requiredHost) ? requiredHost.toLowerCase(Locale.US) : null;
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		if (uri == null || !isChanHostOrRelative(uri) || getThreadNumber(uri) != null) return false;
		List<String> segments = uri.getPathSegments();
		return segments.isEmpty() || segments.size() == 2 && "all".equals(segments.get(0))
				&& "new".equals(segments.get(1));
	}

	@Override
	public boolean isThreadUri(Uri uri) {
		return uri != null && isChanHostOrRelative(uri) && getThreadNumber(uri) != null;
	}

	@Override
	public boolean isAttachmentUri(Uri uri) {
		return uri != null && "https".equalsIgnoreCase(uri.getScheme())
				&& (isImageExtension(uri.getPath()) || isAudioExtension(uri.getPath())
				|| isVideoExtension(uri.getPath()));
	}

	@Override
	public String getBoardName(Uri uri) {
		if (uri == null || !isChanHostOrRelative(uri)) return null;
		String host = uri.getHost();
		if (host != null) {
			host = host.toLowerCase(Locale.US);
			if (host.endsWith(".d3.ru") && !"d3.ru".equals(host)) {
				String boardName = host.substring(0, host.length() - ".d3.ru".length());
				if (BOARD_NAME.matcher(boardName).matches()) return boardName;
			}
		}
		return BOARD_ALL;
	}

	@Override
	public String getThreadNumber(Uri uri) {
		if (uri == null) return null;
		Matcher matcher = THREAD_PATH.matcher(StringUtils.emptyIfNull(uri.getPath()));
		return matcher.matches() ? matcher.group(1) : null;
	}

	@Override
	public String getPostNumber(Uri uri) {
		if (uri == null) return null;
		Matcher matcher = COMMENT_FRAGMENT.matcher(StringUtils.emptyIfNull(uri.getFragment()));
		return matcher.matches() ? matcher.group(1) : null;
	}

	@Override
	public Uri createBoardUri(String boardName, int pageNumber) {
		Uri uri = BOARD_ALL.equals(boardName) ? buildPath()
				: buildPathWithHost(boardName + ".d3.ru");
		return pageNumber > 0 ? uri.buildUpon().appendQueryParameter("page",
				Integer.toString(pageNumber + 1)).build() : uri;
	}

	@Override
	public Uri createThreadUri(String boardName, String threadNumber) {
		return buildPath(threadNumber);
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon()
				.fragment("comment-" + postNumber).build();
	}

	public Uri createBoardsApiUri() {
		return buildPath("api", "domains");
	}

	public Uri createThreadsApiUri(String boardName, int pageNumber) {
		Uri uri = BOARD_ALL.equals(boardName) ? buildPath("api", "posts")
				: buildPath("api", "domains", Uri.encode(boardName), "posts");
		return pageNumber > 0 ? uri.buildUpon().appendQueryParameter("page",
				Integer.toString(pageNumber + 1)).build() : uri;
	}

	public Uri createPostApiUri(String threadNumber) {
		return buildPath("api", "posts", threadNumber);
	}

	public Uri createCommentsApiUri(String threadNumber) {
		return buildPath("api", "posts", threadNumber, "comments");
	}

	@Override
	public NavigationData handleUriClickSpecial(Uri uri) {
		if (uri == null || !isChanHostOrRelative(uri)) return null;
		String threadNumber = getThreadNumber(uri);
		if (threadNumber != null) {
			return new NavigationData(NavigationData.TARGET_POSTS, getBoardName(uri), threadNumber,
					getPostNumber(uri), null);
		}
		String boardName = isBoardUri(uri) ? getBoardName(uri) : null;
		return boardName != null ? new NavigationData(NavigationData.TARGET_THREADS,
				boardName, null, null, null) : null;
	}
}
