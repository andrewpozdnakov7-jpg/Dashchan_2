package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import chan.content.Chan;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Downloads originals into private temporary files, never into the public downloads folder.
 * Only the main-thread owner may install the resulting draft. No posting request is sent. */
public final class RolloverThreadTask extends HttpHolderTask<Void, RolloverThreadTask.Result> {
	public static final class Source {
		public final String uri, name;
		public Source(String uri, String name) { this.uri = uri; this.name = name; }
	}

	public static final class Result {
		public final ArrayList<DraftsStorage.AttachmentDraft> attachments = new ArrayList<>();
		public final ArrayList<String> failed = new ArrayList<>();
		public boolean invalidated;
	}

	private final Chan chan;
	private final List<Source> sources;
	private final int limit;
	private final long generation;
	private final Consumer<Result> callback;
	private long lastAuthorizationCheck;
	private boolean authorizationValid = true;

	public RolloverThreadTask(Chan chan, List<Source> sources, int limit, long generation,
			Consumer<Result> callback) {
		super(chan);
		this.chan = chan;
		this.sources = new ArrayList<>(sources);
		this.limit = Math.max(0, limit);
		this.generation = generation;
		this.callback = callback;
	}

	private boolean authorized() {
		MainApplication context = MainApplication.getInstance();
		return !isCancelled() && !SecurityStore.locked(context) && ProfileGeneration.canWrite(context, generation);
	}

	private boolean downloadCancelled() {
		if (isCancelled()) return true;
		long now = android.os.SystemClock.elapsedRealtime();
		// Avoid reading the security journal for every 8 KiB network chunk.
		if (now - lastAuthorizationCheck >= 500) {
			lastAuthorizationCheck = now;
			authorizationValid = authorized();
		}
		return !authorizationValid;
	}

	@Override protected Result run(HttpHolder holder) {
		Result result = new Result();
		for (Source source : sources) {
			if (!authorized()) { result.invalidated = true; break; }
			if (result.attachments.size() >= limit || source.uri == null) {
				result.failed.add(source.name);
				continue;
			}
			File temporary = null;
			try {
				Uri uri = Uri.parse(source.uri);
				// Only full media cache entries are used, never thumbnails or .part files.
				File cached = CacheManager.getInstance().getMediaFile(uri, true);
				String hash = null;
				if (cached != null && cached.isFile() && cached.length() > 0) {
					hash = DraftsStorage.getInstance().storeAttachmentFile(FileHolder.obtain(cached));
				}
				// Cache eviction during copying is recoverable via the normal original URL.
				if (hash == null) {
					if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
						result.failed.add(source.name);
						continue;
					}
					temporary = File.createTempFile("rollover-", ".tmp", MainApplication.getInstance().getCacheDir());
					RetryableMediaDownload.download(chan, uri, holder, temporary, 0, 15000, 15000,
							new RetryableMediaDownload.Callback() {
								@Override public boolean isCancelled() { return downloadCancelled(); }
							});
					if (!authorized()) { result.invalidated = true; break; }
					if (temporary.length() <= 0) throw new IOException("Empty attachment");
					hash = DraftsStorage.getInstance().storeAttachmentFile(FileHolder.obtain(temporary));
				}
				if (hash == null) throw new IOException("Cannot prepare attachment");
				result.attachments.add(new DraftsStorage.AttachmentDraft(hash, source.name,
						null, false, false, false, false, null));
			} catch (ExtensionException | HttpException | InvalidResponseException | IOException | RuntimeException e) {
				// Report a file by display name only; never log URLs, cookies or user content.
				result.failed.add(source.name);
			} finally {
				if (temporary != null) temporary.delete();
			}
		}
		if (!authorized()) result.invalidated = true;
		return result;
	}

	@Override protected void onComplete(Result result) { callback.accept(result); }

	@Override protected void onCancel(Result result) {
		if (result != null) DraftsStorage.getInstance().discardPreparedAttachmentDrafts(result.attachments);
	}
}
