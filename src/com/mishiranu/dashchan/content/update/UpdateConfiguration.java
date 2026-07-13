package com.mishiranu.dashchan.content.update;

import android.net.Uri;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;

public final class UpdateConfiguration {
	private UpdateConfiguration() {}

	public static boolean isBetaChannel() {
		return Preferences.getUpdateChannel() == Preferences.UpdateChannel.BETA;
	}

	public static String getLegacyManifestUrl() {
		return isBetaChannel() ? BuildConfig.URI_UPDATES_BETA : BuildConfig.URI_UPDATES;
	}

	public static String getApplicationManifestUrl() {
		return isBetaChannel() ? BuildConfig.UPDATE_MANIFEST_BETA_URL : BuildConfig.UPDATE_MANIFEST_URL;
	}

	public static String getGitHubReleasesUrl() {
		return isBetaChannel() ? BuildConfig.UPDATE_GITHUB_BETA_RELEASES_URL
				: BuildConfig.UPDATE_GITHUB_RELEASES_URL;
	}

	public static String getGitHubLatestReleaseUrl() {
		return isBetaChannel() ? BuildConfig.UPDATE_GITHUB_BETA_LATEST_RELEASE_URL
				: BuildConfig.UPDATE_GITHUB_LATEST_RELEASE_URL;
	}

	public static String getBetaMetadataUri() {
		return BuildConfig.GITHUB_URI_METADATA_BETA;
	}

	public static String getBetaMetadataPath() {
		return BuildConfig.GITHUB_PATH_METADATA_BETA;
	}

	public static Uri resolveExtensionUpdateUri(Uri updateUri) {
		if (isBetaChannel() && updateUri != null && normalizeUrl(BuildConfig.URI_UPDATES)
				.equals(normalizeUrl(updateUri.toString()))) {
			return Uri.parse(BuildConfig.URI_UPDATES_BETA);
		}
		return updateUri;
	}

	private static String normalizeUrl(String url) {
		return url != null && url.startsWith("//") ? "https:" + url : url;
	}
}
