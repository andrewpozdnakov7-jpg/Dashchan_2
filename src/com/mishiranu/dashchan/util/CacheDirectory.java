package com.mishiranu.dashchan.util;

import java.io.File;
import java.io.IOException;

public final class CacheDirectory {
	private CacheDirectory() {}

	public static File child(File parent, String name) {
		return parent != null ? new File(parent, name) : null;
	}

	public static boolean prepare(File directory) {
		return directory != null && (directory.isDirectory() || directory.mkdirs() || directory.isDirectory());
	}

	public static boolean isWritable(File directory) {
		if (!prepare(directory)) return false;
		try {
			File probe = File.createTempFile(".writable-", ".tmp", directory);
			probe.delete();
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
