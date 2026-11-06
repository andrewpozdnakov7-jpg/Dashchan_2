package com.mishiranu.dashchan.content.update;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DownloadedApkTest {
	@Test public void installerAcceptsOnlyTheRecordedBytesAndRejectsMissingReceipts() throws Exception {
		Context context = ApplicationProvider.getApplicationContext();
		File file = File.createTempFile("apk-integrity-test-", ".apk", context.getCacheDir());
		byte[] original = {1, 2, 3, 4};
		try {
			Files.write(file.toPath(), original);
			DownloadedApk.record(file, MessageDigest.getInstance("SHA-256").digest(original));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (FileInputStream input = new FileInputStream(file)) {
				DownloadedApk.copyVerified(file, input, output, () -> false);
			}
			assertArrayEquals(original, output.toByteArray());
			Files.write(file.toPath(), new byte[] {4, 3, 2, 1});
			try (FileInputStream input = new FileInputStream(file)) {
				try {
					DownloadedApk.copyVerified(file, input, new ByteArrayOutputStream(), () -> false);
					fail("Same-length APK replacement must be rejected");
				} catch (IOException expected) {}
			}
			DownloadedApk.invalidate(file);
			try (FileInputStream input = new FileInputStream(file)) {
				try {
					DownloadedApk.copyVerified(file, input, new ByteArrayOutputStream(), () -> false);
					fail("No receipt must fail closed");
				} catch (IOException expected) {}
			}
		} finally {
			DownloadedApk.invalidate(file);
			Files.deleteIfExists(file.toPath());
		}
	}
}
