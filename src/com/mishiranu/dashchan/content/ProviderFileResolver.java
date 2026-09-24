package com.mishiranu.dashchan.content;

import java.io.File;
import java.io.IOException;

// URI paths are always relative to an explicit provider root, never absolute paths.
final class ProviderFileResolver {
	private ProviderFileResolver() {}

	static String relativePath(File directory, File file) throws IOException {
		if (directory == null || file == null) {
			throw new IOException("Missing provider root or file");
		}
		String root = directory.getCanonicalPath();
		String prefix = root.endsWith(File.separator) ? root : root + File.separator;
		String path = file.getCanonicalPath();
		if (!path.startsWith(prefix) || path.length() == prefix.length()) {
			throw new IOException("File is outside provider root");
		}
		return path.substring(prefix.length()).replace(File.separatorChar, '/');
	}

	static File resolve(File directory, String relativePath) throws IOException {
		if (directory == null || relativePath == null || relativePath.isEmpty()
				|| relativePath.indexOf('\\') >= 0 || relativePath.indexOf('\0') >= 0) {
			throw new IOException("Invalid provider path");
		}
		for (String part : relativePath.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				throw new IOException("Invalid provider path segment");
			}
		}
		File file = new File(directory, relativePath).getCanonicalFile();
		relativePath(directory, file); // Also rejects symlinks escaping the root.
		if (!file.isFile()) {
			throw new IOException("Provider file is missing");
		}
		return file;
	}
}
