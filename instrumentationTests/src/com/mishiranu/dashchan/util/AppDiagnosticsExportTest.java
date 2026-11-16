package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Disposable export files only; never starts/stops a user's player capture or sends an Intent. */
public class AppDiagnosticsExportTest {
	@Test public void combinesReportsAndPreservesThePreviousReportAcrossAFailedReplacement() throws Exception {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File directory = Files.createTempDirectory(base.getCacheDir().toPath(), "app-diagnostics-test-").toFile();
		Context context = new ContextWrapper(base) { @Override public File getCacheDir() { return directory; } };
		File screen = new File(directory, "screen.txt"), player = new File(directory, "player.txt");
		try {
			Files.write(screen.toPath(), "screen_sentinel=1\n".getBytes(StandardCharsets.UTF_8));
			Files.write(player.toPath(), "player_sentinel=1\n".getBytes(StandardCharsets.UTF_8));
			File report = AppDiagnostics.writeReport(context, 123, screen, player);
			String first = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
			assertTrue(first.contains("[screens_and_input]\navailable=true\nscreen_sentinel=1"));
			assertTrue(first.contains("[player_ui_conversion_and_predictive_back]\navailable=true\nplayer_sentinel=1"));
			File missing = new File(directory, "disappeared.txt") { @Override public boolean isFile() { return true; } };
			try { AppDiagnostics.writeReport(context, 456, screen, missing); fail("Missing part must fail the atomic replacement"); }
			catch (IOException expected) {}
			assertEquals(first, new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8));
			File retry = AppDiagnostics.writeReport(context, 456, screen, player);
			assertTrue(new String(Files.readAllBytes(retry.toPath()), StandardCharsets.UTF_8).contains("startedUtcMillis=456"));
			File partial = AppDiagnostics.writeReport(context, 789, screen, null);
			assertTrue(new String(Files.readAllBytes(partial.toPath()), StandardCharsets.UTF_8)
					.contains("[player_ui_conversion_and_predictive_back]\navailable=false"));
		} finally {
			File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
		}
	}
	@Test public void reportSharingHasTheRecipientAttachmentAndReadGrantWithoutSending() {
		Uri uri = Uri.parse("content://example.invalid/diagnostics.txt");
		Intent intent = DiagnosticReportSharing.reportIntent(uri);
		assertEquals(Intent.ACTION_SEND, intent.getAction()); assertEquals("text/plain", intent.getType());
		assertArrayEquals(new String[]{"andrewpozdnakov7@gmail.com"}, intent.getStringArrayExtra(Intent.EXTRA_EMAIL));
		assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM));
		assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
		assertFalse(intent.hasExtra(Intent.EXTRA_TEXT)); assertFalse(intent.hasExtra(Intent.EXTRA_HTML_TEXT));
	}
}
