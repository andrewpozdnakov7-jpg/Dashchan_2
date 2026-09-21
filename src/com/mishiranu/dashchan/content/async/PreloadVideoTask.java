package com.mishiranu.dashchan.content.async;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.content.CacheManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Optional, bounded download. Never uses the foreground player's shared .part file. */
public final class PreloadVideoTask extends HttpHolderTask<Void, File> {
	private final Chan chan;
	private final Uri uri;
	private final long maxBytes;
	private final ConnectivityManager connectivity;
	private final boolean wifiOnly;
	private final Runnable callback;

	public PreloadVideoTask(Chan chan, Uri uri, long maxBytes, ConnectivityManager connectivity,
			boolean wifiOnly, Runnable callback) {
		super(chan);
		this.chan = chan;
		this.uri = uri;
		this.maxBytes = maxBytes;
		this.connectivity = connectivity;
		this.wifiOnly = wifiOnly;
		this.callback = callback;
	}

	public static boolean isNetworkAllowed(ConnectivityManager connectivity, boolean wifiOnly) {
		if (connectivity == null) return false;
		try {
			NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
			return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					&& (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
							&& !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
		} catch (SecurityException e) {
			return false;
		}
	}

	private boolean allowed() {
		return !isCancelled() && isNetworkAllowed(connectivity, wifiOnly);
	}

	@Override
	protected File run(HttpHolder holder) {
		File temporary = null;
		HttpResponse response = null;
		boolean complete = false;
		try {
			if (!allowed()) return null;
			CacheManager cache = CacheManager.getInstance();
			File destination = cache.getMediaFile(uri, false);
			if (!cache.isCacheAvailable() || destination == null || destination.exists()) return null;
			ChanPerformer.ReadContentResult result = chan.performer.safe().onReadContent(
					new ChanPerformer.ReadContentData(uri, 10000, 10000, holder, -1L, -1L));
			response = result != null ? result.response : null;
			if (response == null || response.getResponseCode() != 200) return null;
			long length = response.getLength();
			// Check actual response headers before opening/reading the body, not just post metadata.
			if (length <= 0 || length > maxBytes || !allowed()) return null;
			temporary = File.createTempFile("video-preload-", ".part", destination.getParentFile());
			try (InputStream input = response.open(); FileOutputStream output = new FileOutputStream(temporary)) {
				byte[] buffer = new byte[8192];
				long received = 0;
				// The gallery's network callback interrupts this holder on a disallowed network.
				// Do not perform a ConnectivityManager binder round-trip for every 8 KiB chunk.
				while (!isCancelled()) {
					int count = input.read(buffer);
					if (count < 0) {
						complete = received == length && allowed();
						break;
					}
					if (received + count > length || received + count > maxBytes || isCancelled()) return null;
					output.write(buffer, 0, count);
					received += count;
				}
			}
			return complete ? temporary : null;
		} catch (IOException | HttpException | ExtensionException | InvalidResponseException e) {
			// Best effort only: no retries, notifications, or media/session URLs in logs.
			complete = false;
			return null;
		} finally {
			if (response != null) response.cleanupAndDisconnect();
			if (!complete && temporary != null) temporary.delete();
		}
	}

	@Override
	protected void onComplete(File temporary) {
		// Publish on the UI thread: a gallery switch cancels this task before starting foreground I/O.
		if (temporary != null) {
			CacheManager cache = CacheManager.getInstance();
			File destination = cache.getMediaFile(uri, false);
			if (allowed() && cache.isCacheAvailable() && destination != null && !destination.exists()
					&& temporary.renameTo(destination)) {
				cache.handleDownloadedFile(destination, true);
			}
			temporary.delete();
		}
		callback.run();
	}

	@Override
	protected void onCancel(File temporary) {
		if (temporary != null) temporary.delete();
	}
}
