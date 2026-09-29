package com.mishiranu.dashchan.content.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Set;
import java.util.function.Consumer;

/** Private attachment copies only. All callers must share the journal's single worker. */
final class OutboxAttachmentCleanup {
	private OutboxAttachmentCleanup() {}

	static boolean isEntryId(String id) {
		return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	private static File checkedRoot(File root) throws IOException {
		if (root == null || Files.isSymbolicLink(root.toPath()) || !root.isDirectory()) {
			throw new IOException("Outgoing attachment root unavailable");
		}
		return root.getCanonicalFile();
	}

	static void delete(File root, String id) throws IOException {
		if (!isEntryId(id)) throw new IOException("Invalid outgoing attachment identity");
		File canonicalRoot = checkedRoot(root);
		File directory = new File(canonicalRoot, id);
		if (Files.isSymbolicLink(directory.toPath()) || !directory.equals(directory.getCanonicalFile())) {
			throw new IOException("Outgoing attachment directory is redirected");
		}
		if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
		if (!directory.isDirectory()) throw new IOException("Outgoing attachment directory unavailable");
		File[] files = directory.listFiles();
		if (files == null) throw new IOException("Cannot list outgoing attachments");
		// No recursive deletion, symlink following, or deletion of journal.db/other metadata.
		for (File file : files) {
			if (!file.getName().matches("[0-9a-f]{64}(\\.bak|\\.new)?")
					|| Files.isSymbolicLink(file.toPath()) || !file.equals(file.getCanonicalFile())
					|| !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("Unexpected outgoing attachment entry");
			}
		}
		for (File file : files) Files.deleteIfExists(file.toPath());
		Files.deleteIfExists(directory.toPath());
	}

	/** The database snapshot retains EVERY non-SENT row, including active/uncertain drafts. */
	static void cleanupUnused(File root, Set<String> retained, Consumer<Exception> failure) throws IOException {
		File canonicalRoot = checkedRoot(root);
		File[] entries = canonicalRoot.listFiles();
		if (entries == null) throw new IOException("Cannot list outgoing attachment root");
		for (File entry : entries) {
			String id = entry.getName();
			if (isEntryId(id) && !retained.contains(id)) {
				try {
					delete(canonicalRoot, id);
				} catch (IOException | SecurityException e) {
					// Keep failed directories for the next open/list; never suppress the failure silently.
					failure.accept(e);
				}
			}
		}
	}
}
