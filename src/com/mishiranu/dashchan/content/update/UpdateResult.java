package com.mishiranu.dashchan.content.update;

import chan.util.StringUtils;

public class UpdateResult {
	public static final int UNKNOWN_VERSION_CODE = -1;

	public enum Source {
		PRIMARY_JSON("primary_json"),
		GITHUB_RELEASES("github_releases");

		public final String value;

		Source(String value) {
			this.value = value;
		}
	}

	public enum Status {
		UPDATE_AVAILABLE,
		NO_UPDATE,
		UPDATE_UNAVAILABLE,
		RELEASE_FOUND,
		ERROR
	}

	public final Status status;
	public final Source source;
	public final int versionCode;
	public final String versionName;
	public final int minSdk;
	public final String title;
	public final String summary;
	public final String releaseNotesUrl;
	public final String downloadPageUrl;
	public final boolean critical;
	public final String errorMessage;

	private UpdateResult(Status status, Source source, int versionCode, String versionName, int minSdk,
			String title, String summary, String releaseNotesUrl, String downloadPageUrl,
			boolean critical, String errorMessage) {
		this.status = status;
		this.source = source;
		this.versionCode = versionCode;
		this.versionName = versionName;
		this.minSdk = minSdk;
		this.title = title;
		this.summary = summary;
		this.releaseNotesUrl = releaseNotesUrl;
		this.downloadPageUrl = downloadPageUrl;
		this.critical = critical;
		this.errorMessage = errorMessage;
	}

	public static UpdateResult available(Source source, int versionCode, String versionName, int minSdk,
			String title, String summary, String releaseNotesUrl, String downloadPageUrl, boolean critical) {
		return new UpdateResult(Status.UPDATE_AVAILABLE, source, versionCode, versionName, minSdk,
				title, summary, releaseNotesUrl, downloadPageUrl, critical, null);
	}

	public static UpdateResult unavailable(Source source, int versionCode, String versionName, int minSdk,
			String title, String summary, String releaseNotesUrl, String downloadPageUrl, boolean critical) {
		return new UpdateResult(Status.UPDATE_UNAVAILABLE, source, versionCode, versionName, minSdk,
				title, summary, releaseNotesUrl, downloadPageUrl, critical, null);
	}

	public static UpdateResult releaseFound(Source source, String versionName, String title, String summary,
			String releaseNotesUrl, String downloadPageUrl) {
		return new UpdateResult(Status.RELEASE_FOUND, source, UNKNOWN_VERSION_CODE, versionName, 0,
				title, summary, releaseNotesUrl, downloadPageUrl, false, null);
	}

	public static UpdateResult noUpdate(Source source) {
		return new UpdateResult(Status.NO_UPDATE, source, UNKNOWN_VERSION_CODE, null, 0,
				null, null, null, null, false, null);
	}

	public static UpdateResult error(String errorMessage) {
		return new UpdateResult(Status.ERROR, null, UNKNOWN_VERSION_CODE, null, 0,
				null, null, null, null, false, errorMessage);
	}

	public int getPreferenceVersionCode() {
		return versionCode > 0 ? versionCode : UNKNOWN_VERSION_CODE;
	}

	public String getOpenPageUrl() {
		return !StringUtils.isEmpty(downloadPageUrl) ? downloadPageUrl : releaseNotesUrl;
	}

	public boolean hasOpenPageUrl() {
		return !StringUtils.isEmpty(getOpenPageUrl());
	}
}
