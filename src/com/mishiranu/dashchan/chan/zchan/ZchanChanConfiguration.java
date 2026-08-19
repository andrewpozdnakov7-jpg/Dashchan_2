package com.mishiranu.dashchan.chan.zchan;

import chan.content.ChanConfiguration;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ZchanChanConfiguration extends ChanConfiguration {
	private static final String STORAGE_INSTALL_ID = "zchan_install_id";
	private static final String KEY_IDENTITY_NAME = "zchan_identity_name";
	private static final String KEY_IDENTITY_BRAND = "zchan_identity_brand";
	private static final String KEY_IDENTITY_MODEL = "zchan_identity_model";
	private static final String KEY_IDENTITY_OS_VERSION = "zchan_identity_os_version";
	private static final String KEY_IDENTITY_FINGERPRINT = "zchan_identity_fingerprint";
	private static final String KEY_IDENTITY_TIMEZONE = "zchan_identity_timezone";
	private static final String KEY_IDENTITY_LANGUAGE = "zchan_identity_language";
	private static final String KEY_IDENTITY_APP_VERSION = "zchan_identity_app_version";
	private static final String KEY_IDENTITY_SOURCE = "zchan_identity_source";

	public static final int IDENTITY_FIELDS_COUNT = 10;

	public static final class IdentityProfile {
		public final String uuid;
		public final String name;
		public final String brand;
		public final String model;
		public final String osVersion;
		public final String fingerprint;
		public final String timezone;
		public final String language;
		public final String appVersion;
		public final String source;

		private IdentityProfile(List<String> values) {
			uuid = values.get(0);
			name = values.get(1);
			brand = values.get(2);
			model = values.get(3);
			osVersion = values.get(4);
			fingerprint = values.get(5);
			timezone = values.get(6);
			language = values.get(7);
			appVersion = values.get(8);
			source = values.get(9);
		}

		public List<String> toValues() {
			return Arrays.asList(uuid, name, brand, model, osVersion, fingerprint, timezone, language,
					appVersion, source);
		}
	}

	public ZchanChanConfiguration() {
		request(OPTION_READ_THREAD_PARTIALLY);
	}

	private static String defaultLanguage() {
		return "ru".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? "ru" : "en";
	}

	private String obtainInstallId() {
		String installId = getCookie(STORAGE_INSTALL_ID);
		if (!isValidUuid(installId)) {
			installId = UUID.randomUUID().toString();
			storeCookie(STORAGE_INSTALL_ID, installId, null);
		}
		return installId;
	}

	public IdentityProfile obtainIdentityProfile() {
		return new IdentityProfile(Arrays.asList(obtainInstallId(),
				get(null, KEY_IDENTITY_NAME, "Android device"),
				get(null, KEY_IDENTITY_BRAND, "android"),
				get(null, KEY_IDENTITY_MODEL, "Android"),
				get(null, KEY_IDENTITY_OS_VERSION, "10"),
				get(null, KEY_IDENTITY_FINGERPRINT, "slooop/android"),
				get(null, KEY_IDENTITY_TIMEZONE, "UTC"),
				get(null, KEY_IDENTITY_LANGUAGE, defaultLanguage()),
				get(null, KEY_IDENTITY_APP_VERSION, BuildConfig.VERSION_NAME),
				get(null, KEY_IDENTITY_SOURCE, "google_play")));
	}

	public boolean isValidIdentityValues(List<String> values) {
		if (values == null || values.size() != IDENTITY_FIELDS_COUNT
				|| values.get(0) == null || !isValidUuid(values.get(0).trim())) {
			return false;
		}
		for (int i = 1; i < values.size(); i++) {
			String value = values.get(i) != null ? values.get(i).trim() : null;
			if (StringUtils.isEmpty(value) || value.length() > 128 || value.indexOf('\r') >= 0
					|| value.indexOf('\n') >= 0) {
				return false;
			}
		}
		return values.get(7).trim().matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?");
	}

	private static boolean isValidUuid(String value) {
		try {
			return !StringUtils.isEmpty(value) && UUID.fromString(value).version() == 4;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public boolean storeIdentityValues(List<String> values) {
		if (!isValidIdentityValues(values)) return false;
		ArrayList<String> normalized = new ArrayList<>(values.size());
		for (String value : values) normalized.add(value.trim());
		storeCookie(STORAGE_INSTALL_ID, normalized.get(0), null);
		set(null, KEY_IDENTITY_NAME, normalized.get(1));
		set(null, KEY_IDENTITY_BRAND, normalized.get(2));
		set(null, KEY_IDENTITY_MODEL, normalized.get(3));
		set(null, KEY_IDENTITY_OS_VERSION, normalized.get(4));
		set(null, KEY_IDENTITY_FINGERPRINT, normalized.get(5));
		set(null, KEY_IDENTITY_TIMEZONE, normalized.get(6));
		set(null, KEY_IDENTITY_LANGUAGE, normalized.get(7));
		set(null, KEY_IDENTITY_APP_VERSION, normalized.get(8));
		set(null, KEY_IDENTITY_SOURCE, normalized.get(9));
		commit();
		return true;
	}

	public IdentityProfile generateIdentityProfile() {
		IdentityProfile current = obtainIdentityProfile();
		String uuid = UUID.randomUUID().toString();
		int osVersion = 10 + Math.abs(uuid.hashCode() % 7);
		IdentityProfile profile = new IdentityProfile(Arrays.asList(uuid, "Android device", "android", "Android",
				Integer.toString(osVersion), "slooop/android/" + uuid.substring(0, 8), "UTC", defaultLanguage(),
				current.appVersion, current.source));
		storeIdentityValues(profile.toValues());
		return profile;
	}

	public IdentityProfile resetIdentityProfile() {
		IdentityProfile current = obtainIdentityProfile();
		IdentityProfile profile = new IdentityProfile(Arrays.asList(current.uuid, "Android device", "android",
				"Android", "10", "slooop/android", "UTC", defaultLanguage(),
				BuildConfig.VERSION_NAME, "google_play"));
		storeIdentityValues(profile.toValues());
		return profile;
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowCatalog = true;
		board.allowPosting = true;
		board.allowDeleting = false;
		board.allowReporting = false;
		return board;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		Posting posting = new Posting();
		posting.allowName = !newThread;
		posting.allowSubject = newThread;
		posting.optionOriginalPoster = !newThread;
		posting.attachmentCount = 4;
		posting.attachmentMimeTypes.add("image/*");
		posting.attachmentMimeTypes.add("video/*");
		return posting;
	}
}
