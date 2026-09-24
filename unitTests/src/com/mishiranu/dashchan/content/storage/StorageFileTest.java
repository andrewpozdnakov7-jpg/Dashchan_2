package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StorageFileTest {
	@Rule public final TemporaryFolder directory = new TemporaryFolder();

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static void write(File file, String value) throws IOException {
		Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void successfulCommitSurvivesRecovery() throws IOException {
		File file = directory.newFile("state.json");
		File backup = new File(directory.getRoot(), "state.backup.json");
		write(file, "OLD");
		StorageFile.write(file, backup, output -> output.write("NEW".getBytes(StandardCharsets.UTF_8)));
		assertEquals("NEW", read(file));
		assertFalse(backup.exists());
		StorageFile.restore(backup, file);
		assertEquals("NEW", read(file));
	}

	@Test
	public void partialWriteFailurePreservesRecoverableBackup() throws IOException {
		File file = directory.newFile("state.json");
		File backup = new File(directory.getRoot(), "state.backup.json");
		write(file, "OLD");
		try {
			StorageFile.write(file, backup, output -> {
				output.write('N');
				throw new IOException("Test disk full");
			});
			fail("Write must report failure");
		} catch (IOException expected) {
			assertFalse(file.exists());
			assertEquals("OLD", read(backup));
		}
		StorageFile.restore(backup, file);
		assertEquals("OLD", read(file));
	}

	@Test
	public void retryAfterFailedWriteCanCommitLatestState() throws IOException {
		File file = new File(directory.getRoot(), "state.json");
		File backup = directory.newFile("state.backup.json");
		write(backup, "OLD");
		StorageFile.write(file, backup, output -> output.write("LATEST".getBytes(StandardCharsets.UTF_8)));
		assertEquals("LATEST", read(file));
		assertFalse(backup.exists());
	}

	@Test
	public void backupRemovalFailureIsNotReportedAsSuccess() throws IOException {
		File file = new File(directory.getRoot(), "state.json");
		File backup = new File(directory.getRoot(), "state.backup.json") {
			@Override public boolean delete() { return false; }
		};
		write(backup, "OLD");
		try {
			StorageFile.write(file, backup, output -> output.write('N'));
			fail("Undeletable backup must fail commit");
		} catch (IOException expected) {
			assertFalse(file.exists());
			assertTrue(backup.exists());
		}
	}

	@Test
	public void interruptedFileIsReplacedByBackupOnRecovery() throws IOException {
		File file = directory.newFile("state.json");
		File backup = directory.newFile("state.backup.json");
		write(file, "PARTIAL");
		write(backup, "OLD");
		StorageFile.restore(backup, file);
		assertEquals("OLD", read(file));
		assertFalse(backup.exists());
	}

	@Test
	public void backupPreparationFailureKeepsOriginalFile() throws IOException {
		File file = new File(directory.getRoot(), "state.json") {
			@Override public boolean renameTo(File destination) { return false; }
		};
		write(file, "OLD");
		File backup = new File(directory.getRoot(), "state.backup.json");
		try {
			StorageFile.write(file, backup, output -> output.write('N'));
			fail("Backup preparation must fail");
		} catch (IOException expected) {
			assertEquals("OLD", read(file));
			assertFalse(backup.exists());
		}
	}
}
