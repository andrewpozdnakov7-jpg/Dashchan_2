package com.mishiranu.dashchan.content.update;

import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GitHubReleaseFallback {
	private static final Pattern[] VERSION_CODE_PATTERNS = {
			Pattern.compile("(?i)\\bversion\\s*code\\b\\s*[:=#-]?\\s*(\\d{3,})"),
			Pattern.compile("(?i)\\bcode\\b\\s*[:=#-]?\\s*(\\d{3,})")
	};

	public static UpdateResult check(String releasesUrl, String latestReleaseUrl) throws Exception {
		if (!StringUtils.isEmpty(releasesUrl)) {
			UpdateResult result = checkReleasesList(releasesUrl);
			if (result != null) {
				return result;
			}
		}
		if (!StringUtils.isEmpty(latestReleaseUrl)) {
			JSONObject release = new JSONObject(UpdateChecker.downloadString(latestReleaseUrl));
			return resultFromRelease(release);
		}
		return null;
	}

	private static UpdateResult checkReleasesList(String releasesUrl) throws Exception {
		JSONArray releases = new JSONArray(UpdateChecker.downloadString(appendPerPage(releasesUrl)));
		for (int i = 0; i < releases.length(); i++) {
			JSONObject release = releases.getJSONObject(i);
			if (!release.optBoolean("draft", false)) {
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
				for (Pattern pattern : VERSION_CODE_PATTERNS) {
					Matcher matcher = pattern.matcher(value);
					if (matcher.find()) {
						try {
							return Integer.parseInt(matcher.group(1));
						} catch (NumberFormatException e) {
							// Try the next pattern/source.
						}
					}
				}
			}
		}
		return null;
	}

	private static String firstLine(String value) {
		if (StringUtils.isEmpty(value)) {
			return null;
		}
		String[] lines = value.split("\\r?\\n");
		for (String line : lines) {
			line = line.trim();
			if (!line.isEmpty()) {
				return line.length() > 240 ? line.substring(0, 240) : line;
			}
		}
		return null;
	}
}
