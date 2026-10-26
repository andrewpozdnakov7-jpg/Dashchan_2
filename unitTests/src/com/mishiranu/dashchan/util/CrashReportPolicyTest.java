package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class CrashReportPolicyTest {
	@Test public void onlyActualCrashesAndAnrOfferReports() {
		for (int reason = 0; reason < 40; reason++) assertEquals(reason == 4 || reason == 5 || reason == 6,
				CrashReportPolicy.crashReason(reason));
	}
	@Test public void reportIdentityCannotEscapeDirectory() {
		assertTrue(CrashReportPolicy.reportId("report-123456-abcdef012345.txt"));
		for (String id : new String[]{null, "", "../report-1-abcdef012345.txt", "/report-1-abcdef012345.txt",
				"report-1-ABCDEF012345.txt", "report-1-abcdef012345.txt/../private", "report-1-abcdef012345.txt.seen"}) {
			assertFalse(CrashReportPolicy.reportId(id));
		}
	}
	@Test public void exceptionMessagesAndSuppressedDataAreNeverSerialized() {
		RuntimeException cause = new RuntimeException("cookie=SECRET; https://private.invalid/post");
		RuntimeException error = new RuntimeException("PIN1234 email@example.invalid", cause);
		error.addSuppressed(new RuntimeException("another SECRET"));
		String text = CrashReportPolicy.stack(error);
		assertTrue(text.contains("java.lang.RuntimeException"));
		for (String secret : new String[]{"cookie=", "SECRET", "private.invalid", "PIN1234", "email@", "another"}) assertFalse(text.contains(secret));
	}
	@Test public void arbitraryFilenamesAreNotExportedAsSourcePaths() {
		RuntimeException error = new RuntimeException();
		error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.Screen", "open", "/private/photos/secret.jpg", 3)});
		String text = CrashReportPolicy.stack(error);
		assertTrue(text.contains("example.Screen.open(source:3)")); assertFalse(text.contains("secret"));
	}
	@Test public void stackAndCauseDepthAreBounded() {
		StackTraceElement[] frames = new StackTraceElement[4096];
		Arrays.fill(frames, new StackTraceElement("example.Screen", "open", "Screen.java", 42));
		Throwable error = null;
		for (int i = 0; i < 40; i++) { RuntimeException next = new RuntimeException("not exported", error); next.setStackTrace(frames); error = next; }
		String text = CrashReportPolicy.stack(error);
		assertTrue(text.length() < 30000); assertFalse(text.contains("not exported"));
	}
	@Test public void cyclicCauseDoesNotLoop() {
		RuntimeException cycle = new RuntimeException() { @Override public synchronized Throwable getCause() { return this; } };
		assertTrue(CrashReportPolicy.stack(cycle).length() < 10000);
	}
	@Test public void processSummaryIsSmallAndVersioned() {
		String summary = CrashReportPolicy.summary("abcdef0123456789abcdef0123456789", 11490, "3.2.69", "PIP_ENTER", 37, "github:ndebug");
		assertTrue(summary.getBytes(StandardCharsets.US_ASCII).length <= 128);
		String[] parts = CrashReportPolicy.parseSummary(summary);
		assertNotNull(parts); assertEquals("11490", parts[2]); assertEquals("3.2.69", parts[3]); assertEquals("PIP_ENTER", parts[4]);
		assertEquals("37", parts[5]); assertEquals("github:ndebug", parts[6]);
		assertNull(CrashReportPolicy.parseSummary("other-app|some user text"));
		assertNull(CrashReportPolicy.parseSummary(summary + "\nsecret"));
	}
	@Test public void longestSummaryStillFitsAndroidLimit() {
		String longText = new String(new char[1000]).replace('\0', 'a');
		String summary = CrashReportPolicy.summary("abcdef0123456789abcdef0123456789", Integer.MAX_VALUE,
				longText, longText, 999, longText);
		assertTrue(summary.getBytes(StandardCharsets.US_ASCII).length <= 128);
		assertNotNull(CrashReportPolicy.parseSummary(summary));
	}
	@Test public void onlyStrictJavaFramesAreAcceptedFromAnr() {
		assertEquals("  at example.Screen.open(Screen.java:42)", CrashReportPolicy.anrFrame("    at example.Screen.open(Screen.java:42)"));
		for (String line : new String[]{"\"user's private thread name\"", "cookie=SECRET", "at https://private.invalid/p(SECRET)",
				"  at example.Screen.open(Screen.java:42) token=SECRET"}) assertNull(CrashReportPolicy.anrFrame(line));
	}
	@Test public void shareNamesAndLifetimeAreRestricted() {
		assertTrue(CrashReportPolicy.shareName("share-abcdef0123456789abcdef0123456789.txt"));
		assertFalse(CrashReportPolicy.shareName("../preferences.xml"));
		assertTrue(CrashReportPolicy.freshShare(1000, 1100));
		assertFalse(CrashReportPolicy.freshShare(1000, 999));
		assertFalse(CrashReportPolicy.freshShare(1000, 1001 + CrashReportPolicy.SHARE_LIFETIME));
	}
}
