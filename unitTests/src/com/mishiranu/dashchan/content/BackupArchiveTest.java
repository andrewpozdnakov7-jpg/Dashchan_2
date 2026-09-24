package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BackupArchiveTest {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	@Test public void successIncludesCentralDirectory() throws Exception {
		File file = folder.newFile();
		BackupArchive.write(new FileOutputStream(file), zip -> {
			zip.putNextEntry(new ZipEntry("favorites.json"));
			zip.write("{\"data\":[]}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		});
		try (ZipFile zip = new ZipFile(file)) {
			assertNotNull(zip.getEntry("favorites.json"));
			assertEquals(11L, zip.getEntry("favorites.json").getSize());
		}
	}

	@Test public void trailerFailureDoesNotReturnSuccess() throws Exception {
		boolean[] fail = {false};
		OutputStream output = new OutputStream() {
			@Override public void write(int value) throws IOException {
				if (fail[0]) throw new IOException("injected trailer failure");
			}
		};
		try {
			BackupArchive.write(output, zip -> {
				zip.putNextEntry(new ZipEntry("data"));
				zip.write(1);
				zip.closeEntry();
				fail[0] = true;
			});
			fail("Must propagate finalization failure");
		} catch (IOException expected) { assertTrue(fail[0]); }
	}

	@Test public void closeFailureDoesNotReturnSuccess() throws Exception {
		OutputStream output = new ByteArrayOutputStream() {
			@Override public void close() throws IOException { throw new IOException("injected close failure"); }
		};
		try {
			BackupArchive.write(output, zip -> {});
			fail("Must propagate output close failure");
		} catch (IOException expected) { assertEquals("injected close failure", expected.getMessage()); }
	}

	@Test public void entryFailureDoesNotReturnSuccess() throws Exception {
		try {
			BackupArchive.write(new ByteArrayOutputStream(), zip -> { throw new IOException("entry"); });
			fail("Must propagate entry failure");
		} catch (IOException expected) { assertEquals("entry", expected.getMessage()); }
	}
}
