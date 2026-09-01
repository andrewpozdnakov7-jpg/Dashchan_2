package com.mishiranu.dashchan.content.update;

import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GitHubReleaseFallback {
	public static UpdateResult check(String releasesUrl, String latestReleaseUrl,
			boolean includePrereleases) throws Exception {
		if (!StringUtils.isEmpty(releasesUrl)) {
			UpdateResult result = checkReleasesList(releasesUrl, includePrereleases);
			if (result != null) {
				return result;
			}
		}
		if (!StringUtils.isEmpty(latestReleaseUrl)) {
			JSONObject release = new JSONObject(UpdateChecker.downloadString(latestReleaseUrl));
			if (!includePrereleases && release.optBoolean("prerelease", false)) {
				return UpdateResult.noUpdate(UpdateResult.Source.GITHUB_RELEASES);
			}
			return resultFromRelease(release);
		}
		return null;
	}

	private static UpdateResult checkReleasesList(String releasesUrl, boolean includePrereleases) throws Exception {
		JSONArray releases = new JSONArray(UpdateChecker.downloadString(appendPerPage(releasesUrl)));
		for (int i = 0; i < releases.length(); i++) {
			JSONObject release = releases.getJSONObject(i);
			if (!release.optBoolean("draft", false) &&
					(includePrereleases || !release.optBoolean("prerelease", false))) {
				return resultFromRelease(release);
			}
		}
		return UpdateResult.noUpdate(UpdateResult.Source.GITHUB_RELEASES);
	}

	private static String appendPerPage(String url) {
		if (url.contains("per_page=")) {
			return url;
		}
		return url + (url.contains("?") ? "&" : "?") + "per_page=10";
	}

	private static UpdateResult resultFromRelease(JSONObject release) throws JSONException {
		if (release.optBoolean("draft", false)) {
			return UpdateResult.noUpdate(UpdateResult.Source.GITHUB_RELEASES);
		}
		String htmlUrl = release.optString("html_url", null);
		String tagName = release.optString("tag_name", null);
		String name = release.optString("name", null);
		String body = release.optString("body", null);
		String versionName = !StringUtils.isEmpty(tagName) ? tagName : name;
		String title = !StringUtils.isEmpty(name) ? name : tagName;
		String summary = firstLine(body);
		Integer versionCode = extractVersionCode(name, tagName, body);
		if (versionCode != null) {
			if (versionCode > BuildConfig.VERSION_CODE) {
				return UpdateResult.available(UpdateResult.Source.GITHUB_RELEASES, versionCode,
						versionName, 0, title, summary, htmlUrl, htmlUrl, false);
			}
			return UpdateResult.noUpdate(UpdateResult.Source.GITHUB_RELEASES);
		}
		return UpdateResult.releaseFound(UpdateResult.Source.GITHUB_RELEASES, versionName,
				title, summary, htmlUrl, htmlUrl);
	}

	private static Integer extractVersionCode(String... values) {
		for (String value : values) {
			if (!StringUtils.isEmpty(value)) {
				String lower = value.toLowerCase(Locale.US);
				Integer code = extractLabeledNumber(value, lower, "version", "code");
				if (code == null) code = extractLabeledNumber(value, lower, "code");
				if (code == null) code = extractChannelNumber(value, lower, "beta");
				if (code == null) code = extractChannelNumber(value, lower, "test");
				if (code != null) return code;
			}
		}
		return null;
	}

	private static boolean isWordCharacter(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static int skipWhitespace(String value, int index) {
		while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
		return index;
	}

	private static Integer parseNumber(String value, int start) {
		int end = start;
		while (end < value.length() && value.charAt(end) >= '0' && value.charAt(end) <= '9') end++;
		if (end - start < 3 || end < value.length() && isWordCharacter(value.charAt(end))) return null;
		try {
			return Integer.parseInt(value.substring(start, end));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer extractLabeledNumber(String value, String lower, String... words) {
		int fromIndex = 0;
		while (fromIndex < lower.length()) {
			int index = lower.indexOf(words[0], fromIndex);
			if (index < 0) return null;
			if (index > 0 && isWordCharacter(lower.charAt(index - 1))) {
				fromIndex = index + 1;
				continue;
			}
			int end = index + words[0].length();
			if (end < lower.length() && isWordCharacter(lower.charAt(end))) {
				fromIndex = index + 1;
				continue;
			}
			boolean matches = true;
			for (int i = 1; i < words.length; i++) {
				int separatorStart = end;
				end = skipWhitespace(lower, end);
				if (end == separatorStart) {
					matches = false;
					break;
				}
				String word = words[i];
				if (!lower.startsWith(word, end) || end + word.length() < lower.length() &&
						isWordCharacter(lower.charAt(end + word.length()))) {
					matches = false;
					break;
				}
				end += word.length();
			}
			if (matches) {
				end = skipWhitespace(lower, end);
				if (end < lower.length() && ":=#-".indexOf(lower.charAt(end)) >= 0) end++;
				end = skipWhitespace(lower, end);
				Integer number = parseNumber(value, end);
				if (number != null) return number;
			}
			fromIndex = index + 1;
		}
		return null;
	}

	private static Integer extractChannelNumber(String value, String lower, String channel) {
		int fromIndex = 0;
		while (fromIndex < lower.length()) {
			int index = lower.indexOf(channel, fromIndex);
			if (index < 0) return null;
			if (index == 0 || lower.charAt(index - 1) == '-' || lower.charAt(index - 1) == '_') {
				int end = index + channel.length();
				if (end < lower.length() && (lower.charAt(end) == '-' || lower.charAt(end) == '_')) end++;
				Integer number = parseNumber(value, end);
				if (number != null) return number;
			}
			fromIndex = index + 1;
		}
		return null;
	}

	private static String firstLine(String value) {
		if (StringUtils.isEmpty(value)) {
			return null;
		}
		int start = 0;
		while (start <= value.length()) {
			int end = value.indexOf('\n', start);
			if (end < 0) end = value.length();
			String line = value.substring(start, end);
			if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
			line = line.trim();
			if (!line.isEmpty()) {
				return line.length() > 240 ? line.substring(0, 240) : line;
			}
			if (end == value.length()) break;
			start = end + 1;
		}
		return null;
	}
}
