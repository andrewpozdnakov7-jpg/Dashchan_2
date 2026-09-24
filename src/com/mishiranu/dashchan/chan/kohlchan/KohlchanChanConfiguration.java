package com.mishiranu.dashchan.chan.kohlchan;

import chan.content.ChanConfiguration;
import chan.util.CommonUtils;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class KohlchanChanConfiguration extends ChanConfiguration {
	private static final String KEY_NAMES = "posting_names";
	private static final String KEY_DELETE = "posting_delete";
	private static final String KEY_CODE = "posting_code";
	private static final String KEY_FILES = "posting_files";
	private static final String KEY_LENGTH = "posting_length";
	private static final String KEY_FILE_SIZE = "posting_file_size";
	private static final String KEY_THREAD_FILE = "posting_thread_file";

	public KohlchanChanConfiguration() {
		setDefaultName("Bernd");
		addCaptchaType("kohlchan");
	}

	@Override
	public Board obtainBoardConfiguration(String boardName) {
		Board board = new Board();
		board.allowCatalog = true;
		board.allowSearch = false;
		board.allowPosting = true;
		board.allowDeleting = get(boardName, KEY_DELETE, true);
		board.allowReporting = false;
		return board;
	}

	@Override
	public Captcha obtainCustomCaptchaConfiguration(String captchaType) {
		if (!"kohlchan".equals(captchaType)) return null;
		Captcha captcha = new Captcha();
		captcha.title = "Kohlchan";
		captcha.input = Captcha.Input.ALL;
		captcha.validity = Captcha.Validity.IN_BOARD_SEPARATELY;
		return captcha;
	}

	@Override
	public Posting obtainPostingConfiguration(String boardName, boolean newThread) {
		Posting posting = new Posting();
		posting.allowName = posting.allowTripcode = get(boardName, KEY_NAMES, true);
		posting.allowSubject = true;
		posting.optionSage = true;
		posting.optionSpoiler = true;
		posting.maxCommentLength = get(boardName, KEY_LENGTH, 16384);
		posting.attachmentCount = get(boardName, KEY_FILES, 4);
		posting.attachmentMimeTypes.addAll(Arrays.asList("image/png", "image/jpeg", "image/gif", "image/bmp",
				"image/webp", "video/webm", "video/mp4", "video/ogg", "video/x-m4v", "audio/mpeg", "audio/ogg",
				"audio/webm", "audio/flac", "audio/x-m4a", "audio/opus", "application/zip", "application/pdf",
				"application/x-7z-compressed", "application/epub+zip", "text/plain"));
		return posting;
	}

	@Override
	public Deleting obtainDeletingConfiguration(String boardName) {
		Deleting deleting = new Deleting();
		deleting.password = true;
		deleting.multiplePosts = true;
		return deleting;
	}

	boolean isCodeEnabled(String boardName) {
		return get(boardName, KEY_CODE, false);
	}

	boolean requiresThreadFile(String boardName) {
		return get(boardName, KEY_THREAD_FILE, false);
	}

	long getMaxFileSize(String boardName) {
		// The public JSON provides a formatted size, not a byte count. Unknown formats
		// are left to server validation rather than guessing a potentially wrong limit.
		String value = get(boardName, KEY_FILE_SIZE, "");
		String[] parts = value.trim().toUpperCase(Locale.US).split("\\s+");
		if (parts.length != 2) return 0;
		long multiplier;
		switch (parts[1]) {
			case "B": multiplier = 1; break;
			case "KB": multiplier = 1024; break;
			case "MB": multiplier = 1024 * 1024; break;
			case "GB": multiplier = 1024 * 1024 * 1024L; break;
			default: return 0;
		}
		try {
			double bytes = Double.parseDouble(parts[0]) * multiplier;
			return Double.isFinite(bytes) && bytes > 0 && bytes <= Long.MAX_VALUE ? (long) bytes : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	void updateBoard(String boardName, JSONObject object) {
		String title = CommonUtils.optJsonString(object, "boardName");
		if (title != null && !title.isEmpty()) storeBoardTitle(boardName, title);
		String description = CommonUtils.optJsonString(object, "boardDescription");
		if (description != null) storeBoardDescription(boardName, description);
		if (object.optInt("pageCount") > 0) storePagesCount(boardName, object.optInt("pageCount"));
		// Board-list and thread JSON omit some settings: do not reset learned values.
		JSONArray settings = object.optJSONArray("settings");
		if (settings != null) {
			boolean names = true, delete = true, code = false, threadFile = false;
			for (int i = 0; i < settings.length(); i++) {
				switch (settings.optString(i)) {
					case "forceAnonymity": names = false; break;
					case "blockDeletion": delete = false; break;
					case "allowCode": code = true; break;
					case "requireThreadFile": threadFile = true; break;
				}
			}
			set(boardName, KEY_NAMES, names);
			set(boardName, KEY_DELETE, delete);
			set(boardName, KEY_CODE, code);
			set(boardName, KEY_THREAD_FILE, threadFile);
		}
		if (object.has("forceAnonymity")) set(boardName, KEY_NAMES, !object.optBoolean("forceAnonymity"));
		if (object.has("maxFileCount")) set(boardName, KEY_FILES, Math.max(0, object.optInt("maxFileCount", 4)));
		if (object.has("maxMessageLength")) set(boardName, KEY_LENGTH,
				Math.max(0, object.optInt("maxMessageLength", 16384)));
		if (object.has("maxFileSize")) set(boardName, KEY_FILE_SIZE, object.optString("maxFileSize"));
	}
}
