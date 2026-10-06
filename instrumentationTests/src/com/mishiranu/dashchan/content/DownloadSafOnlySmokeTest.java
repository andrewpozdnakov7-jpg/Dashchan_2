package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.net.Uri;
import android.util.Pair;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.util.DataFile;
import com.mishiranu.dashchan.BuildConfig;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Model/URI smoke checks, not a replacement for download + picker + external-viewer tests.
 * Does not request/revoke grants or write downloads; the normal getter may validate/recover a grant. */
@RunWith(AndroidJUnit4.class)
public class DownloadSafOnlySmokeTest {
	@Test public void downloadsAndChildrenNeverHaveRegularFileFallback() {
		DataFile root = DataFile.obtain(DataFile.Target.DOWNLOADS, null);
		assertSafOnly(root);
		assertSafOnly(root.getChild("saf-smoke-" + UUID.randomUUID() + ".jpg"));
	}

	private static void assertSafOnly(DataFile file) {
		assertEquals(DataFile.Target.DOWNLOADS, file.getTarget());
		assertTrue(file.getTarget().isExternal());
		Pair<File, Uri> fileOrUri = file.getFileOrUri();
		assertNull(fileOrUri.first);
		if (fileOrUri.second != null) {
			assertEquals("content", fileOrUri.second.getScheme());
			assertTrue(file.exists());
		}
	}

	@Test public void cacheAndUpdatesRetainRegularFileBackend() {
		for (DataFile.Target target : new DataFile.Target[] {DataFile.Target.CACHE, DataFile.Target.UPDATES}) {
			assertFalse(target.isExternal());
			DataFile file = DataFile.obtain(target, "saf-smoke.jpg");
			assertEquals(target, file.getTarget());
			assertNotNull(file.getFileOrUri().first);
			assertNull(file.getFileOrUri().second);
		}
	}

	@Test public void oldDownloadsProviderUriIsRejectedForReadAndMetadata() throws Exception {
		Uri oldUri = Uri.parse("content://" + BuildConfig.FILE_PROVIDER_AUTHORITY + "/downloads/old-file.jpg");
		FileProvider provider = new FileProvider();
		for (int operation = 0; operation < 3; operation++) {
			try {
				switch (operation) {
					case 0: provider.openFile(oldUri, "r").close(); break;
					case 1: provider.getType(oldUri); break;
					case 2: provider.query(oldUri, null, null, null, null).close(); break;
				}
				fail("Removed downloads path must not be exposed");
			} catch (IllegalArgumentException expected) {
				assertEquals("Unknown provider path", expected.getMessage());
			}
		}
	}

	@Test public void oldProviderUriNeverAcceptsWriteMode() throws Exception {
		Uri oldUri = Uri.parse("content://" + BuildConfig.FILE_PROVIDER_AUTHORITY + "/downloads/old-file.jpg");
		for (String mode : new String[] {"w", "rw", "rwt", "wa"}) {
			try {
				new FileProvider().openFile(oldUri, mode);
				fail("Removed downloads path must not accept writes: " + mode);
			} catch (FileNotFoundException expected) {
				assertEquals("Provider is read-only", expected.getMessage());
			}
		}
	}
}
