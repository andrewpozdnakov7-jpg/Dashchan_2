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
		// Reattaching an existing player must never replay an old persisted seek.
		if (file == null || players.containsKey(player) || player.getDuration() <= 0L) return;
		String key = StringUtils.formatHex(Hasher.getInstanceSha256()
				.calculate(file.getAbsolutePath().getBytes(StandardCharsets.UTF_8)));
		players.put(player, key);
		latestKey = key;
		latestPlayer = new java.lang.ref.WeakReference<>(player);
		if (!Preferences.isRememberLastVideoPosition()) return;
		LastVideoPosition bookmark = LastVideoPosition.decode(Preferences.getSavedLastVideoPosition());
		long position = bookmark != null ? bookmark.resumePosition(key, player.getDuration()) : 0L;
		if (position > 0L) player.setPosition(position);
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
