package com.mishiranu.dashchan;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class C {
	public static final Set<String> IMAGE_EXTENSIONS;
	public static final Set<String> AUDIO_EXTENSIONS;
	public static final Set<String> VIDEO_EXTENSIONS;

	public static final Set<String> OPENABLE_VIDEO_EXTENSIONS;

	public static final Map<String, String> EXTENSION_TRANSFORMATION;

	@SafeVarargs
	private static <T> Set<T> immutableSet(T... items) {
		HashSet<T> hashSet = new HashSet<>();
		for (T item : items) {
			if (item != null) {
				hashSet.add(item);
			}
		}
		return Collections.unmodifiableSet(hashSet);
	}

	static {
		IMAGE_EXTENSIONS = immutableSet("jpg", "jpe", "jpeg", "png", "apng", "gif", "webp", "bmp", "svg");
		AUDIO_EXTENSIONS = immutableSet("mp3", "ogg", "flac", "wav");
		VIDEO_EXTENSIONS = immutableSet("webm", "mkv", "mp4");
		OPENABLE_VIDEO_EXTENSIONS = immutableSet("webm", "mkv", "mp4");
		HashMap<String, String> extensionTransformation = new HashMap<>();
		extensionTransformation.put("jpg", "jpeg");
		extensionTransformation.put("jpe", "jpeg");
		extensionTransformation.put("apng", "png");
		EXTENSION_TRANSFORMATION = Collections.unmodifiableMap(extensionTransformation);
	}

	public static final String DEFAULT_DOWNLOAD_PATH = "/Download/Dashchan/";

	public static final String ACTION_POSTING = "com.mishiranu.dashchan.action.POSTING";
	public static final String ACTION_GALLERY = "com.mishiranu.dashchan.action.GALLERY";
	public static final String ACTION_PLAYER = "com.mishiranu.dashchan.action.PLAYER";
	public static final String ACTION_BROWSER = "com.mishiranu.dashchan.action.BROWSER";

	public static final int REQUEST_CODE_ATTACH = 1;
	public static final int REQUEST_CODE_OPEN_URI_TREE = 2;

	public static final int NOTIFICATION_ID_POSTING = 1;
	public static final int NOTIFICATION_ID_DOWNLOADING = 2;
	public static final int NOTIFICATION_ID_AUDIO_PLAYER = 3;
	public static final int NOTIFICATION_ID_UPDATES = 4;
	public static final int NOTIFICATION_ID_REPLIES = 5;

	public static final String NOTIFICATION_CHANNEL_POSTING = "posting";
	public static final String NOTIFICATION_CHANNEL_POSTING_COMPLETE = "postingComplete";
	public static final String NOTIFICATION_CHANNEL_DOWNLOADING = "downloading";
	public static final String NOTIFICATION_CHANNEL_DOWNLOADING_COMPLETE = "downloadingComplete";
	public static final String NOTIFICATION_CHANNEL_AUDIO_PLAYER = "audioPlayer";
	public static final String NOTIFICATION_CHANNEL_UPDATES = "updates";
	public static final String NOTIFICATION_CHANNEL_REPLIES = "replies";

	public static final String EXTRA_BOARD_NAME = "com.mishiranu.dashchan.extra.BOARD_NAME";
	public static final String EXTRA_CHAN_NAME = "com.mishiranu.dashchan.extra.CHAN_NAME";
	public static final String EXTRA_FAIL_RESULT = "com.mishiranu.dashchan.extra.FAIL_RESULT";
	public static final String EXTRA_POST_NUMBER = "com.mishiranu.dashchan.extra.POST_NUMBER";
	public static final String EXTRA_FROM_CLIENT = "com.mishiranu.dashchan.extra.FROM_CLIENT";
	public static final String EXTRA_THREAD_NUMBER = "com.mishiranu.dashchan.extra.THREAD_NUMBER";
	public static final String EXTRA_UPDATE_DATA_MAP = "com.mishiranu.dashchan.extra.UPDATE_DATA_MAP";
}
