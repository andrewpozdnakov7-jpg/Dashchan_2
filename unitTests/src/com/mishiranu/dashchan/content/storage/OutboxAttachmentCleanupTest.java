package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OutboxAttachmentCleanupTest {
	@Rule public final TemporaryFolder temporary = new TemporaryFolder();
	private static final String FIRST = "00000000-0000-0000-0000-000000000001";
	private static final String SECOND = "00000000-0000-0000-0000-000000000002";
	private static final String HASH = "a".repeat(64);

	private static File attachment(File root, String id, String suffix) throws IOException {
		File directory = new File(root, id);
		Files.createDirectories(directory.toPath());
		File file = new File(directory, HASH + suffix);
		Files.write(file.toPath(), new byte[] {1, 2, 3});
		return file;
	}

	@Test public void orphanAfterRowDeletionIsRemovedButLiveDraftIsKept() throws Exception {
		File root = temporary.newFolder();
		File orphan = attachment(root, FIRST, "");
		File retained = attachment(root, SECOND, "");
		File journal = new File(root, "journal.db");
		Files.write(journal.toPath(), new byte[] {9});
		List<Exception> failures = new ArrayList<>();
		OutboxAttachmentCleanup.cleanupUnused(root, Set.of(SECOND), failures::add);
		assertTrue(failures.isEmpty());
		assertFalse(orphan.getParentFile().exists());
		assertTrue(retained.isFile());
		assertTrue(journal.isFile());
	}

	@Test public void partiallyCompletedDeletionAndAtomicFileRemnantsAreRetryable() throws Exception {
		File root = temporary.newFolder();
		File original = attachment(root, FIRST, "");
		attachment(root, FIRST, ".new");
		attachment(root, FIRST, ".bak");
		Files.delete(original.toPath()); // Model interruption after only one file was removed.
		OutboxAttachmentCleanup.delete(root, FIRST);
		OutboxAttachmentCleanup.delete(root, FIRST);
		assertFalse(new File(root, FIRST).exists());
	}

	@Test public void failedDirectoryStaysForRetryAndDoesNotBlockOtherCleanup() throws Exception {
		File root = temporary.newFolder();
		File blocked = new File(new File(root, FIRST), HASH);
		Files.createDirectories(blocked.toPath()); // Unexpected directory: do not recursively delete it.
		attachment(root, SECOND, "");
		List<Exception> failures = new ArrayList<>();
		OutboxAttachmentCleanup.cleanupUnused(root, Set.of(), failures::add);
		assertEquals(1, failures.size());
		assertTrue(blocked.isDirectory());
		assertFalse(new File(root, SECOND).exists());
		Files.delete(blocked.toPath());
		failures.clear();
		OutboxAttachmentCleanup.cleanupUnused(root, Set.of(), failures::add);
		assertTrue(failures.isEmpty());
		assertFalse(new File(root, FIRST).exists());
	}

	@Test public void invalidIdentityCannotEscapeRoot() throws Exception {
		File root = temporary.newFolder();
		for (String id : new String[] {null, "", "../outside", "journal.db", "-".repeat(36)}) {
			try {
				OutboxAttachmentCleanup.delete(root, id);
				fail("Invalid identity must fail");
			} catch (IOException expected) {}
		}
		assertTrue(root.isDirectory());
	}

	@Test public void unknownFilesArePreservedAndReported() throws Exception {
		File root = temporary.newFolder();
		File directory = new File(root, FIRST);
		Files.createDirectory(directory.toPath());
		File unexpected = new File(directory, "unknown.txt");
		Files.write(unexpected.toPath(), new byte[] {1});
		try {
			OutboxAttachmentCleanup.delete(root, FIRST);
			fail("Unexpected files must not be silently deleted");
		} catch (IOException expected) {}
		assertTrue(unexpected.exists());
	}

	@Test public void directorySymlinkIsNotFollowed() throws Exception {
		File root = temporary.newFolder();
		File outside = temporary.newFolder();
		File original = new File(outside, HASH);
		Files.write(original.toPath(), new byte[] {1});
		try {
			Files.createSymbolicLink(new File(root, FIRST).toPath(), outside.toPath());
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assume.assumeNoException(e);
		}
		List<Exception> failures = new ArrayList<>();
		OutboxAttachmentCleanup.cleanupUnused(root, Set.of(), failures::add);
		assertEquals(1, failures.size());
		assertTrue(original.exists());
	}
}
