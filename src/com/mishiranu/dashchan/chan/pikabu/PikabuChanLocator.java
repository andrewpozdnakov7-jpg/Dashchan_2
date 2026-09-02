package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import android.util.Base64;
import chan.content.ChanLocator;
import chan.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class PikabuChanLocator extends ChanLocator {
	public static final String BOARD_HOT = "hot";
	public static final String BOARD_BEST = "best";
	public static final String BOARD_NEW = "new";
	private static final String BOARD_COMMUNITY_PREFIX = "community_";
	private static final String BOARD_TAG_PREFIX = "tag_";
	private static final String BOARD_PROFILE_PREFIX = "profile_";
	private static final String BOARD_BROWSE_PREFIX = "browse_";

	public static final String BROWSE_ORIGINAL = "original-stories";
	public static final String BROWSE_TEXT = "text-stories";
	public static final String BROWSE_IMAGES = "image-stories";
	public static final String BROWSE_VIDEO = "video-stories";
	public static final String BROWSE_REPLIES = "reply-stories";
	public static final String BROWSE_SERIES = "series-stories";
	public static final String BROWSE_LONG = "long-stories";

	private static final Pattern THREAD_PATH = Pattern.compile("/story/(?:[^/?#]*_)?(\\d+)/?");
	private static final Pattern COMMENT_FRAGMENT = Pattern.compile("comment_(\\d+)");
	private static final Pattern COMMUNITY_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
	private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	private static final Set<String> BROWSE_NAMES = new HashSet<>(Arrays.asList(BROWSE_ORIGINAL,
			BROWSE_TEXT, BROWSE_IMAGES, BROWSE_VIDEO, BROWSE_REPLIES, BROWSE_SERIES, BROWSE_LONG));

	public PikabuChanLocator() {
		addChanHost("pikabu.ru");
		addConvertableChanHost("www.pikabu.ru");
		setHttpsMode(HttpsMode.HTTPS_ONLY);
	}

	@Override
	public boolean isBoardUri(Uri uri) {
		if (!isChanHostOrRelative(uri)) return false;
		List<String> segments = uri.getPathSegments();
		if (segments.isEmpty()) return true;
		if (segments.size() == 1) {
			String first = segments.get(0);
			return BOARD_BEST.equals(first) || BOARD_NEW.equals(first) || isProfilePathSegment(first);
		}
		if (segments.size() == 2) {
			String first = segments.get(0);
			String second = segments.get(1);
			return "community".equals(first) && isCommunityName(second)
					|| "tag".equals(first) && isTagName(second)
					|| "browse".equals(first) && BROWSE_NAMES.contains(second);
		}
		if (segments.size() == 3) {
			String first = segments.get(0);
			String second = segments.get(1);
			String third = segments.get(2);
			return "community".equals(first) && isCommunityName(second)
					&& ("hot".equals(third) || "best".equals(third))
					|| "tag".equals(first) && isTagName(second)
					&& ("hot".equals(third) || "best".equals(third) || "new".equals(third));
		}
		return false;
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
		if (isThreadUri(uri)) return BOARD_HOT;
		if (!isBoardUri(uri)) return null;
		List<String> segments = uri.getPathSegments();
		if (segments.isEmpty()) return BOARD_HOT;
		String first = segments.get(0);
		switch (first) {
			case BOARD_BEST:
				return segments.size() == 1 ? BOARD_BEST : null;
			case BOARD_NEW:
				return segments.size() == 1 ? BOARD_NEW : null;
			case "community":
				return segments.size() >= 2 && isCommunityName(segments.get(1))
						? createCommunityBoardName(segments.get(1)) : null;
			case "tag":
				return segments.size() >= 2 && isTagName(segments.get(1))
						? createTagBoardName(segments.get(1)) : null;
			case "browse":
				return segments.size() == 2 && BROWSE_NAMES.contains(segments.get(1))
						? createBrowseBoardName(segments.get(1)) : null;
			default:
				if (segments.size() == 1 && isProfilePathSegment(first)) {
					return createProfileBoardName(first.substring(1));
				}
				return null;
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
		if (isCommunityBoardName(boardName)) {
			uri = buildPath("community", getBoardValue(boardName, BOARD_COMMUNITY_PREFIX));
		} else if (isTagBoardName(boardName)) {
			uri = buildPath("tag", Uri.encode(getBoardValue(boardName, BOARD_TAG_PREFIX)), "hot");
		} else if (isProfileBoardName(boardName)) {
			uri = buildPath("@" + getBoardValue(boardName, BOARD_PROFILE_PREFIX));
		} else if (isBrowseBoardName(boardName)) {
			uri = buildPath("browse", getBrowseValue(boardName));
		} else switch (boardName) {
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

	public Uri createCommunitiesUri() {
		return buildPath("communities");
	}

	public Uri createTagsUri() {
		return buildPath("tags");
	}

	public Uri createSearchUri(String query, int pageNumber) {
		Uri uri;
		String normalized = StringUtils.emptyIfNull(query).trim();
		if (normalized.startsWith("#") && isTagName(normalized.substring(1))) {
			uri = buildPath("tag", Uri.encode(normalized.substring(1)), "hot");
		} else if (normalized.startsWith("@") && isProfileName(normalized.substring(1))) {
			uri = buildPath("@" + normalized.substring(1));
		} else {
			uri = buildPath("search").buildUpon().appendQueryParameter("q", normalized).build();
		}
		return pageNumber > 0 ? uri.buildUpon().appendQueryParameter("page",
				Integer.toString(pageNumber + 1)).build() : uri;
	}

	public String getExpectedSearchFeedMode(String query) {
		String normalized = StringUtils.emptyIfNull(query).trim();
		return normalized.startsWith("@") && isProfileName(normalized.substring(1)) ? "profile" : "search";
	}

	public String getExpectedFeedMode(String boardName) {
		if (isCommunityBoardName(boardName)) return "community";
		if (isTagBoardName(boardName)) return "search";
		if (isProfileBoardName(boardName)) return "profile";
		if (isBrowseBoardName(boardName)) {
			switch (getBrowseValue(boardName)) {
				case BROWSE_ORIGINAL:
					return "authors";
				case BROWSE_TEXT:
				case BROWSE_IMAGES:
				case BROWSE_VIDEO:
					return "browsing_content_type";
				case BROWSE_REPLIES:
					return "reply_stories";
				case BROWSE_SERIES:
					return "browsing_series";
				case BROWSE_LONG:
					return "long_stories";
			}
		}
		return boardName;
	}

	public static boolean isSupportedBoardName(String boardName) {
		return BOARD_HOT.equals(boardName) || BOARD_BEST.equals(boardName) || BOARD_NEW.equals(boardName)
				|| isCommunityBoardName(boardName) || isTagBoardName(boardName)
				|| isProfileBoardName(boardName) || isBrowseBoardName(boardName);
	}

	public static String createCommunityBoardName(String community) {
		return isCommunityName(community) ? BOARD_COMMUNITY_PREFIX + encodeBoardValue(community) : null;
	}

	public static String createTagBoardName(String tag) {
		return isTagName(tag) ? BOARD_TAG_PREFIX + encodeBoardValue(tag) : null;
	}

	public static String createProfileBoardName(String profile) {
		return isProfileName(profile) ? BOARD_PROFILE_PREFIX + encodeBoardValue(profile) : null;
	}

	public static String createBrowseBoardName(String browse) {
		return BROWSE_NAMES.contains(browse) ? BOARD_BROWSE_PREFIX + browse : null;
	}

	public static boolean isCommunityBoardName(String boardName) {
		return isEncodedBoardName(boardName, BOARD_COMMUNITY_PREFIX, PikabuChanLocator::isCommunityName);
	}

	public static boolean isTagBoardName(String boardName) {
		return isEncodedBoardName(boardName, BOARD_TAG_PREFIX, PikabuChanLocator::isTagName);
	}

	public static boolean isProfileBoardName(String boardName) {
		return isEncodedBoardName(boardName, BOARD_PROFILE_PREFIX, PikabuChanLocator::isProfileName);
	}

	public static boolean isBrowseBoardName(String boardName) {
		return boardName != null && boardName.startsWith(BOARD_BROWSE_PREFIX)
				&& BROWSE_NAMES.contains(getBrowseValue(boardName));
	}

	public static String getDynamicBoardTitle(String boardName) {
		if (isCommunityBoardName(boardName)) return getBoardValue(boardName, BOARD_COMMUNITY_PREFIX);
		if (isTagBoardName(boardName)) return "#" + getBoardValue(boardName, BOARD_TAG_PREFIX);
		if (isProfileBoardName(boardName)) return "@" + getBoardValue(boardName, BOARD_PROFILE_PREFIX);
		return null;
	}

	private interface ValueValidator {
		boolean isValid(String value);
	}

	private static boolean isEncodedBoardName(String boardName, String prefix, ValueValidator validator) {
		if (boardName == null || !boardName.startsWith(prefix)) return false;
		String encoded = boardName.substring(prefix.length());
		if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9_-]+")) return false;
		String value = getBoardValue(boardName, prefix);
		return value != null && encoded.equals(encodeBoardValue(value)) && validator.isValid(value);
	}

	private static String getBoardValue(String boardName, String prefix) {
		try {
			byte[] data = Base64.decode(boardName.substring(prefix.length()),
					Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
			return new String(data, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String encodeBoardValue(String value) {
		return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
				Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
	}

	private static String getBrowseValue(String boardName) {
		return boardName.substring(BOARD_BROWSE_PREFIX.length());
	}

	private static boolean isCommunityName(String value) {
		return value != null && COMMUNITY_NAME.matcher(value).matches();
	}

	private static boolean isProfilePathSegment(String value) {
		return value != null && value.startsWith("@") && isProfileName(value.substring(1));
	}

	private static boolean isProfileName(String value) {
		return value != null && PROFILE_NAME.matcher(value).matches();
	}

	private static boolean isTagName(String value) {
		if (StringUtils.isEmpty(value) || value.length() > 128 || !value.equals(value.trim())
				|| value.indexOf('/') >= 0) return false;
		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i))) return false;
		}
		return true;
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

	public Uri createStoryVoteUri() {
		return buildPath("ajax", "vote_story.php");
	}

	public Uri createCommentVoteUri() {
		return buildPath("ajax", "vote_comment.php");
	}

	@Override
	public NavigationData handleUriClickSpecial(Uri uri) {
		if (uri == null || !isChanHostOrRelative(uri)) return null;
		List<String> segments = uri.getPathSegments();
		if (segments.size() == 1 && "search".equals(segments.get(0))) {
			String query = uri.getQueryParameter("q");
			if (!StringUtils.isEmpty(query)) {
				return new NavigationData(NavigationData.TARGET_SEARCH, BOARD_HOT, null, null, query);
			}
		} else if (segments.size() == 3 && "community".equals(segments.get(0))
				&& "search".equals(segments.get(2)) && isCommunityName(segments.get(1))) {
			String query = uri.getQueryParameter("q");
			if (StringUtils.isEmpty(query)) {
				String tag = uri.getQueryParameter("t");
				if (!StringUtils.isEmpty(tag)) query = "#" + tag;
			}
			if (!StringUtils.isEmpty(query)) {
				return new NavigationData(NavigationData.TARGET_SEARCH,
						createCommunityBoardName(segments.get(1)), null, null, query);
			}
		}
		return null;
	}

	@Override
	public Uri createPostUri(String boardName, String threadNumber, String postNumber) {
		return createThreadUri(boardName, threadNumber).buildUpon()
				.appendQueryParameter("cid", postNumber).fragment("comment_" + postNumber).build();
	}
}
