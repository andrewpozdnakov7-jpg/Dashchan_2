package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrivacyResetFilesTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	@Test public void cleanupIsIdempotentKeepsThemesAndNeverTouchesSiblingDownloads() throws Exception {
		File storage = temporary.newFolder("storage");
		File themes = new File(storage, "themes.json"); Files.write(themes.toPath(), new byte[]{1});
		File history = new File(storage, "my-posts.backup.json"); Files.write(history.toPath(), new byte[]{2});
		File nested = new File(storage, "attachments"); assertTrue(nested.mkdir());
		Files.write(new File(nested, "photo").toPath(), new byte[]{3});
		File downloaded = temporary.newFile("downloaded-video.mp4");
		PrivacyResetFiles.clearChildren(storage, PrivacyResetPolicy::keepStorageFile);
		PrivacyResetFiles.clearChildren(storage, PrivacyResetPolicy::keepStorageFile);
		assertTrue(themes.isFile()); assertFalse(history.exists()); assertFalse(nested.exists()); assertTrue(downloaded.isFile());
	}
	@Test public void unexpectedFileInsteadOfDirectoryFailsRatherThanReportingSuccess() throws Exception {
		File file = temporary.newFile("not-a-directory");
		try { PrivacyResetFiles.clearChildren(file, name -> false); fail("Must fail closed"); }
		catch (IOException expected) { assertTrue(file.isFile()); }
	}
}
