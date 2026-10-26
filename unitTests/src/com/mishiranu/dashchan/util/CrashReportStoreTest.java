package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Synthetic temporary files only. Does not install a crash handler or terminate any process. */
public class CrashReportStoreTest {
	@Rule public final TemporaryFolder temporary = new TemporaryFolder();
	private static final String ID = "report-123456-abcdef012345.txt";
	@Test public void completeReportIsReadableAndNoTemporaryFileRemains() throws Exception {
		File root = temporary.newFolder(); CrashReportStore store = new CrashReportStore(root);
		store.write(ID, "synthetic report\n", System.currentTimeMillis());
		assertEquals("synthetic report\n", store.read(ID)); assertEquals(1, store.list().size());
		assertEquals(1, root.listFiles().length);
	}
	@Test public void oversizeReportIsRejectedWithoutPublishingPartialData() throws Exception {
		CrashReportStore store = new CrashReportStore(temporary.newFolder());
		try { store.write(ID, new String(new char[CrashReportPolicy.MAX_BYTES + 1]), System.currentTimeMillis()); fail(); }
		catch (IOException expected) {}
		assertNull(store.resolve(ID)); assertTrue(store.list().isEmpty());
	}
	@Test public void offeredStateSurvivesStoreRecreation() throws Exception {
		File root = temporary.newFolder(); CrashReportStore store = new CrashReportStore(root);
		store.write(ID, "synthetic", System.currentTimeMillis());
		assertFalse(store.offered(ID)); assertTrue(store.markOffered(ID));
		assertTrue(new CrashReportStore(root).offered(ID));
	}
	@Test public void deletionRemovesReportAndMarkerButNotCursor() throws Exception {
		File root = temporary.newFolder(); CrashReportStore store = new CrashReportStore(root);
		store.cursor(10000); store.advanceCursor(12000);
		store.write(ID, "synthetic", System.currentTimeMillis()); store.markOffered(ID); store.delete(ID);
		assertTrue(store.list().isEmpty()); assertFalse(store.offered(ID)); assertEquals(12000, new CrashReportStore(root).cursor(99999));
	}
	@Test public void freshCursorDoesNotImportOldProfileHistory() throws Exception {
		CrashReportStore store = new CrashReportStore(temporary.newFolder());
		assertEquals(10000, store.cursor(10000)); assertEquals(10000, store.cursor(20000));
	}
	@Test public void countRetentionIsBounded() throws Exception {
		CrashReportStore store = new CrashReportStore(temporary.newFolder());
		for (int i = 0; i < 30; i++) {
			String id = String.format(java.util.Locale.US, "report-%d-%012x.txt", i + 1, i + 1);
			store.write(id, "synthetic", System.currentTimeMillis());
		}
		assertEquals(CrashReportPolicy.MAX_REPORTS, store.list().size());
	}
	@Test public void ageRetentionLeavesUnrelatedFilesAlone() throws Exception {
		File root = temporary.newFolder(); CrashReportStore store = new CrashReportStore(root);
		long now = System.currentTimeMillis(); store.write(ID, "synthetic", now);
		assertTrue(store.resolve(ID).setLastModified(now - CrashReportPolicy.MAX_AGE - 1000));
		File unrelated = new File(root, "unrelated.txt"); Files.write(unrelated.toPath(), new byte[]{1});
		store.prune(now); assertNull(store.resolve(ID)); assertTrue(unrelated.isFile());
	}
	@Test public void sessionDedupRequiresAnActualJavaReport() throws Exception {
		CrashReportStore store = new CrashReportStore(temporary.newFolder());
		String session = "abcdef0123456789abcdef0123456789";
		store.write(ID, "header\nKind: NATIVE\nSession: " + session + "\n", System.currentTimeMillis());
		assertFalse(store.containsSession(session));
		store.write("report-123457-abcdef012346.txt", "header\nKind: TEST_JAVA\nSession: " + session + "\n", System.currentTimeMillis());
		assertTrue(store.containsSession(session));
	}
	@Test public void invalidIdentityCannotReadWriteOrDeleteOtherFiles() throws Exception {
		CrashReportStore store = new CrashReportStore(temporary.newFolder());
		for (String id : new String[]{"../private", "/private", "report-1-abcdef012345.txt/../private"}) {
			try { store.write(id, "bad", System.currentTimeMillis()); fail(); } catch (IOException expected) {}
			try { store.read(id); fail(); } catch (IOException expected) {}
			try { store.delete(id); fail(); } catch (IOException expected) {}
		}
	}
	@Test public void symlinksCannotExposeAnOutsideFile() throws Exception {
		File root = temporary.newFolder(); File outside = temporary.newFile();
		Files.write(outside.toPath(), "private synthetic value".getBytes(StandardCharsets.UTF_8));
		try { Files.createSymbolicLink(new File(root, ID).toPath(), outside.toPath()); }
		catch (IOException | UnsupportedOperationException | SecurityException e) { Assume.assumeNoException(e); }
		try { new CrashReportStore(root).read(ID); fail(); } catch (IOException expected) {}
		assertEquals("private synthetic value", new String(Files.readAllBytes(outside.toPath()), StandardCharsets.UTF_8));
	}
}
