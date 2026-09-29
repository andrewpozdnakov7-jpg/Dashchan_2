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
import com.mishiranu.dashchan.content.async.PreloadMediaTask;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.Executor;

/** One bounded, UI-thread-owned queue for the visible gallery, never for the background/PiP player. */
final class MediaPreloader {
	private static final Executor EXECUTOR = ConcurrentUtils.newSingleThreadPool(20000, "MediaPreloader", null);
	private final GalleryInstance gallery;
	private final ConnectivityManager connectivity;
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private PreloadMediaTask task;
	private Entry currentEntry;
	private ConnectivityManager.NetworkCallback networkCallback;
	private boolean resumed;
	private int generation;

	private static final class Entry {
		final Uri uri;
		final boolean image;
		final long maxBytes;
		final String network;

		Entry(Uri uri, boolean image, long maxBytes, String network) {
			this.uri = uri;
			this.image = image;
			this.maxBytes = maxBytes;
			this.network = network;
		}
	}

	MediaPreloader(GalleryInstance gallery) {
		this.gallery = gallery;
		connectivity = (ConnectivityManager) gallery.context.getApplicationContext()
				.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	void setResumed(boolean resumed) {
		this.resumed = resumed;
		if (!resumed) stop();
	}

	void stop() {
		generation++;
		queue.clear();
		currentEntry = null;
		if (task != null) {
			task.cancel();
			task = null;
		}
		if (networkCallback != null) {
			try {
				connectivity.unregisterNetworkCallback(networkCallback);
			} catch (IllegalArgumentException ignored) {
				// It may already have been unregistered by the system.
			}
			networkCallback = null;
		}
	}

	void start(GalleryItem current) {
		stop();
		if (!resumed || current == null || gallery.callback.isGalleryMode() || connectivity == null) return;
		int index = gallery.galleryItems.indexOf(current);
		if (index < 0) return;
		Chan chan = Chan.get(gallery.chanName);
		HashSet<Uri> seen = new HashSet<>();
		seen.add(current.getFileUri(chan));
		int images = Preferences.isImagePreload() ? Preferences.getImagePreloadCount() : 0;
		int videos = Preferences.isVideoPreload() && Preferences.isUseVideoPlayer()
				? Preferences.getVideoPreloadCount() : 0;
		for (int i = index + 1; i < gallery.galleryItems.size() && (images > 0 || videos > 0); i++) {
			GalleryItem item = gallery.galleryItems.get(i);
			boolean image = item.isImage(chan);
			if (image) {
				if (images <= 0) continue;
				images--;
			} else if (item.isVideo(chan)) {
				if (videos <= 0) continue;
				videos--;
				if (!item.isOpenableVideo(chan)) continue;
			} else continue;
			// Cached, oversized and duplicate attachments consume their original slot.
			long maxBytes = image ? Preferences.getImagePreloadMaxBytes() : Preferences.getVideoPreloadMaxBytes();
			if (item.size > maxBytes) continue;
			String network = image ? Preferences.getImagePreloadNetwork()
					: Preferences.isVideoPreloadWifiOnly() ? "wifi" : "all";
			Uri uri = item.getFileUri(chan);
			if (uri == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
					|| !seen.add(uri)) continue;
			File cached = CacheManager.getInstance().getMediaFile(uri, false);
			if (cached != null && !cached.exists()) queue.add(new Entry(uri, image, maxBytes, network));
		}
		if (queue.isEmpty()) return;
		networkCallback = new ConnectivityManager.NetworkCallback() {
			private void check() {
				if (networkCallback == this && currentEntry != null && !allowed(currentEntry)) {
					if (task != null) task.cancel();
					task = null;
					currentEntry = null;
					next();
				}
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

	private boolean allowed(Entry entry) {
		return resumed && !gallery.callback.isGalleryMode()
				&& (entry.image ? Preferences.isImagePreload() : Preferences.isVideoPreload() && Preferences.isUseVideoPlayer())
				&& PreloadMediaTask.isNetworkAllowed(connectivity, entry.network);
	}

	private void next() {
		Entry entry;
		while ((entry = queue.poll()) != null) {
			if (!allowed(entry)) continue;
			currentEntry = entry;
			int expectedGeneration = generation;
			Entry expectedEntry = entry;
			task = new PreloadMediaTask(Chan.getPreferred(gallery.chanName, entry.uri), entry.uri, entry.maxBytes,
					connectivity, entry.network, () -> {
						if (generation != expectedGeneration || currentEntry != expectedEntry) return;
						task = null;
						currentEntry = null;
						next();
					});
			task.execute(EXECUTOR);
			return;
		}
		stop();
	}
}
