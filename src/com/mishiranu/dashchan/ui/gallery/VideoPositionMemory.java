package com.mishiranu.dashchan.ui.gallery;

import chan.util.StringUtils;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.util.Hasher;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.WeakHashMap;

/** Main-thread bookmark ownership follows the decoder through rotation and PiP transfers. */
final class VideoPositionMemory {
	private static final WeakHashMap<VideoPlayer, String> players = new WeakHashMap<>();
	private static String latestKey;
	private static java.lang.ref.WeakReference<VideoPlayer> latestPlayer = new java.lang.ref.WeakReference<>(null);

	private VideoPositionMemory() {}

	static void restoreOnce(VideoPlayer player, File file) {
		restoreOnce(player, file, false);
	}

	static long requestedPosition(File file) {
		if (file == null || !Preferences.isRememberLastVideoPosition()) return 0L;
		LastVideoPosition bookmark = LastVideoPosition.decode(Preferences.getSavedLastVideoPosition());
		return bookmark != null && bookmark.key.equals(key(file)) ? bookmark.position : 0L;
	}

	private static String key(File file) {
		return StringUtils.formatHex(Hasher.getInstanceSha256()
				.calculate(file.getAbsolutePath().getBytes(StandardCharsets.UTF_8)));
	}

	static void restoreOnce(VideoPlayer player, File file, boolean positionPrepared) {
		// Reattaching an existing player must never replay an old persisted seek.
		if (file == null || players.containsKey(player) || player.getDuration() <= 0L) return;
		String key = key(file);
		players.put(player, key);
		latestKey = key;
		latestPlayer = new java.lang.ref.WeakReference<>(player);
		if (!Preferences.isRememberLastVideoPosition()) return;
		LastVideoPosition bookmark = LastVideoPosition.decode(Preferences.getSavedLastVideoPosition());
		long position = bookmark != null ? bookmark.resumePosition(key, player.getDuration()) : 0L;
		if (position > 0L && !positionPrepared) player.setPosition(position);
		// Opening another playable video replaces the single bookmark, even if it is closed immediately.
		Preferences.setSavedLastVideoPosition(new LastVideoPosition(key, position).encode());
	}

	static void save(VideoPlayer player, boolean completed) {
		if (player == null || latestPlayer.get() != player || !Preferences.isRememberLastVideoPosition()) return;
		String key = players.get(player);
		if (key == null || !key.equals(latestKey)) return;
		long duration = player.getDuration();
		if (duration <= 0L) return;
		Preferences.setSavedLastVideoPosition(LastVideoPosition.capture(key, player.getPosition(), duration,
				completed).encode());
	}
}
