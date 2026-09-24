package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class BackupDrainTest {
	private static byte[] zip(byte[] data) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			ZipEntry entry = new ZipEntry("entry");
			entry.setMethod(ZipEntry.STORED);
			entry.setSize(data.length);
			CRC32 crc = new CRC32();
			crc.update(data);
			entry.setCrc(crc.getValue());
			zip.putNextEntry(entry);
			zip.write(data);
			zip.closeEntry();
		}
		return output.toByteArray();
	}

	@Test public void drainCountsBytesAndUsesBlockReads() throws IOException {
		int[] calls = new int[2];
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zip(new byte[20000]))) {
			@Override public int read() throws IOException { calls[0]++; return super.read(); }
			@Override public int read(byte[] b, int off, int len) throws IOException {
				calls[1]++;
				return super.read(b, off, len);
			}
		}) {
			assertNotNull(zip.getNextEntry());
			BackupManager.ArchiveLimits limits = new BackupManager.ArchiveLimits();
			BackupManager.LimitedEntryInputStream input = new BackupManager.LimitedEntryInputStream(zip, limits);
			BackupManager.drainEntry(input);
			assertEquals(20000, input.getEntrySize());
			assertEquals(20000, limits.total);
			assertEquals(0, calls[0]);
			assertTrue(calls[1] < 100);
		}
	}

	@Test public void sharedLimitAllowsExactSizeButRejectsExtraByte() throws IOException {
		for (int size : new int[] {3, 4}) {
			try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zip(new byte[size])))) {
				zip.getNextEntry();
				BackupManager.ArchiveLimits limits = new BackupManager.ArchiveLimits();
				limits.total = 1024L * 1024L * 1024L - 3;
				BackupManager.LimitedEntryInputStream input = new BackupManager.LimitedEntryInputStream(zip, limits);
				if (size == 3) BackupManager.drainEntry(input);
				else assertThrows(IOException.class, () -> BackupManager.drainEntry(input));
				assertEquals(1024L * 1024L * 1024L, limits.total);
			}
		}
	}

	@Test public void cancellationIsNotSwallowed() throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zip(new byte[3])))) {
			zip.getNextEntry();
			BackupManager.LimitedEntryInputStream input =
					new BackupManager.LimitedEntryInputStream(zip, new BackupManager.ArchiveLimits());
			Thread.currentThread().interrupt();
			try {
				assertThrows(InterruptedIOException.class, () -> BackupManager.drainEntry(input));
				assertTrue(Thread.currentThread().isInterrupted());
			} finally { Thread.interrupted(); }
		}
	}

	@Test public void badCrcAndTruncatedEntryAreRejected() throws IOException {
		byte[] damaged = zip(new byte[10]);
		// Local header (30 bytes) + ASCII name (5 bytes); corrupt stored payload.
		damaged[35] ^= 1;
		byte[] truncated = java.util.Arrays.copyOf(zip(new byte[10]), 38);
		for (byte[] data : new byte[][] {damaged, truncated}) {
			try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
				zip.getNextEntry();
				BackupManager.LimitedEntryInputStream input =
						new BackupManager.LimitedEntryInputStream(zip, new BackupManager.ArchiveLimits());
				assertThrows(IOException.class, () -> BackupManager.drainEntry(input));
			}
		}
	}
}
