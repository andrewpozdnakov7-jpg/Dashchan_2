package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import androidx.annotation.NonNull;
import chan.content.Chan;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.PreloadVideoTask;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;

/** UI-thread-owned queue, scoped to the visible gallery player. */
final class VideoPreloader {
	private final GalleryInstance gallery;
	private final ConnectivityManager connectivity;
	private final ArrayDeque<Uri> queue = new ArrayDeque<>();
	private PreloadVideoTask task;
	private ConnectivityManager.NetworkCallback networkCallback;
	private boolean wifiOnly;
	private long maxBytes;

	VideoPreloader(GalleryInstance gallery) {
		this.gallery = gallery;
		connectivity = (ConnectivityManager) gallery.context.getApplicationContext()
				.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	void stop() {
		queue.clear();
		if (task != null) {
			task.cancel();
			task = null;
		}
		if (networkCallback != null) {
			connectivity.unregisterNetworkCallback(networkCallback);
			networkCallback = null;
		}
	}

	void start(GalleryItem current) {
		stop();
		if (!Preferences.isVideoPreload() || current == null || gallery.callback.isGalleryMode()) return;
		wifiOnly = Preferences.isVideoPreloadWifiOnly();
		maxBytes = Preferences.getVideoPreloadMaxBytes();
		if (!PreloadVideoTask.isNetworkAllowed(connectivity, wifiOnly)) return;
		int index = gallery.galleryItems.indexOf(current);
		if (index < 0) return;
		Chan chan = Chan.get(gallery.chanName);
		HashSet<Uri> seen = new HashSet<>();
		seen.add(current.getFileUri(chan));
		int remaining = Preferences.getVideoPreloadCount();
		for (int i = index + 1; i < gallery.galleryItems.size() && remaining > 0; i++) {
			GalleryItem item = gallery.galleryItems.get(i);
			if (!item.isVideo(chan)) continue;
			remaining--; // Oversized, cached and unsupported videos still occupy their original slot.
			if (!item.isOpenableVideo(chan) || item.size > maxBytes) continue;
			Uri uri = item.getFileUri(chan);
			if (uri == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
					|| !seen.add(uri)) continue;
			File cached = CacheManager.getInstance().getMediaFile(uri, false);
			if (cached != null && !cached.exists()) queue.add(uri);
		}
		if (queue.isEmpty()) return;
		networkCallback = new ConnectivityManager.NetworkCallback() {
			private void check() {
				if (networkCallback == this && !PreloadVideoTask.isNetworkAllowed(connectivity, wifiOnly)) stop();
			}

			@Override
			public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
				check();
			}

			@Override
			public void onLost(@NonNull Network network) {
				check();
			}
		};
		try {
			connectivity.registerDefaultNetworkCallback(networkCallback, ConcurrentUtils.HANDLER);
		} catch (SecurityException | IllegalArgumentException e) {
			networkCallback = null;
			queue.clear();
			return;
		}
		next();
	}

	private void next() {
		if (!Preferences.isVideoPreload() || !PreloadVideoTask.isNetworkAllowed(connectivity, wifiOnly)) {
			stop();
			return;
		}
		Uri uri = queue.poll();
		if (uri == null) {
			stop();
			return;
		}
		task = new PreloadVideoTask(Chan.getPreferred(gallery.chanName, uri), uri, maxBytes,
				connectivity, wifiOnly, () -> {
					task = null;
					next();
				});
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}
}
