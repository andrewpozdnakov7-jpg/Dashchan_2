package com.mishiranu.dashchan.content;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Pair;
import chan.util.DataFile;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Archive-scoped I/O adapter. Never opens existing files for writing. */
public final class LocalArchiveMediaStore implements LocalArchiveMediaPlan.Storage {
	private final LocalArchiveManager.Item previous;
	private final String resources;
	private final HashMap<String, String> previousSources = new HashMap<>();
	public LocalArchiveMediaStore(LocalArchiveManager.Item previous, String resources) {
		this.previous = previous; this.resources = resources;
	}
	public List<String> existingPaths() {
		ArrayList<String> paths = new ArrayList<>();
		for (String directory : new String[] {"src", "thumb"}) {
			List<DataFile> children = DataFile.obtain(DataFile.Target.DOWNLOADS,
					LocalArchiveManager.DIRECTORY_ARCHIVE + '/' + resources + '/' + directory).getChildren();
			if (children != null) for (DataFile file : children) {
				paths.add(resources + '/' + directory + '/' + file.getName());
			}
		}
		return paths;
	}
	private DataFile file(String path) {
		if (!(LocalArchiveMediaPlan.child(resources, "src", path) || LocalArchiveMediaPlan.child(resources, "thumb", path))) {
			throw new IllegalArgumentException("Invalid archive resource path");
		}
		return DataFile.obtain(DataFile.Target.DOWNLOADS, LocalArchiveManager.DIRECTORY_ARCHIVE + '/' + path);
	}
	@Override public boolean exists(String path) { return file(path).exists(); }
	@Override public boolean complete(String path, long expectedSize) {
		long size = size(path);
		return size > 0 && (expectedSize <= 0 || size == expectedSize);
	}
	/** Exact persisted byte length; stream-count only if a provider cannot report it. Worker-thread only. */
	public long size(String path) {
		try {
			DataFile file = file(path);
			if (!file.exists() || file.isDirectory()) return -1;
			Pair<File, Uri> target = file.getFileOrUri();
			long size = -1;
			if (target.first != null) size = target.first.length();
			else if (target.second != null) {
				try (Cursor cursor = MainApplication.getInstance().getContentResolver().query(target.second,
						new String[] {OpenableColumns.SIZE}, null, null, null)) {
					if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0);
				}
			}
			if (size >= 0) return size;
			try (InputStream input = file.openInputStream()) {
				byte[] buffer = new byte[8192];
				long count = 0;
				int read;
				while ((read = input.read(buffer)) != -1) {
					if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
					count += read;
				}
				return count;
			}
		} catch (IOException | RuntimeException e) { return -1; }
	}
	@Override public String previousSource(String path, long expectedSize) {
		if (previous == null || !previous.hasZip()) return null;
		String key = path + '\0' + expectedSize;
		if (previousSources.containsKey(key)) return previousSources.get(key);
		String source = null;
		try (InputStream input = LocalArchiveManager.openZipResource(previous, path)) {
			if (input != null) {
				// Read through the entry so a truncated ZIP cannot cause an endless local-only retry.
				byte[] buffer = new byte[8192];
				long size = 0;
				int count;
				while ((count = input.read(buffer)) != -1) {
					if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
					size += count;
				}
				if (size > 0 && (expectedSize <= 0 || size == expectedSize)) {
					source = LocalArchiveManager.createResourceUri(previous, path).buildUpon()
							.appendQueryParameter("zipOnly", "true").build().toString();
				}
			}
		} catch (IOException | RuntimeException ignored) {}
		previousSources.put(key, source);
		return source;
	}
}
