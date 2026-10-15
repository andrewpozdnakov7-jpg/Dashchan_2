package com.mishiranu.dashchan.content.security;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Predicate;

/** Filesystem-only part, independently testable without killing processes or touching an app profile. */
public final class PrivacyResetFiles {
	private PrivacyResetFiles() {}
	public static void clearChildren(File root, Predicate<String> keep) throws IOException {
		if (Files.isSymbolicLink(root.toPath())) throw new IOException("Unexpected storage link");
		if (!root.exists()) return;
		File[] children = root.listFiles();
		if (children == null) throw new IOException("Cannot enumerate cleanup target");
		for (File child : children) if (!keep.test(child.getName())) delete(child);
	}
	public static void delete(File target) throws IOException {
		if (Files.isSymbolicLink(target.toPath())) {
			Files.delete(target.toPath());
			return;
		}
		if (!target.exists()) return;
		if (target.isDirectory()) clearChildren(target, name -> false);
		if (!target.delete()) throw new IOException("Cannot remove application data");
	}
}
