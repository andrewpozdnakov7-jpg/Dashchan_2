package com.mishiranu.dashchan.content.translation;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public class VerifiedModelCopyTest {
	private static final byte[] DATA = "abc".getBytes(StandardCharsets.UTF_8);
	private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

	@Test public void exactSizeAndHashSucceedWithShortReads() throws IOException {
		InputStream input = new ByteArrayInputStream(DATA) {
			@Override public synchronized int read(byte[] b, int off, int len) {
				return super.read(b, off, Math.min(1, len));
			}
		};
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AtomicLong progress = new AtomicLong();
		VerifiedModelCopy.copy(input, output, DATA.length, HASH, progress::set);
		assertArrayEquals(DATA, output.toByteArray());
		assertEquals(DATA.length, progress.get());
	}

	@Test public void extraByteIsRejectedBeforeWritingBeyondLimit() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				new ByteArrayInputStream(new byte[] {'a', 'b', 'c', 'd'}), output, 3, HASH, null));
		assertTrue(output.size() <= 3);
	}

	@Test public void truncatedInputAndWrongHashAreRejected() {
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				new ByteArrayInputStream(DATA), new ByteArrayOutputStream(), 4, HASH, null));
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				new ByteArrayInputStream(DATA), new ByteArrayOutputStream(), 3, "00", null));
	}

	@Test public void interruptionIsPreserved() {
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedIOException.class, () -> VerifiedModelCopy.copy(
					new ByteArrayInputStream(DATA), new ByteArrayOutputStream(), 3, HASH, null));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}
	}

	@Test public void inputAndOutputFailuresPropagate() {
		InputStream brokenInput = new InputStream() {
			@Override public int read() throws IOException { throw new IOException("Test disconnect"); }
		};
		OutputStream brokenOutput = new OutputStream() {
			@Override public void write(int b) throws IOException { throw new IOException("Test disk full"); }
		};
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				brokenInput, new ByteArrayOutputStream(), 3, HASH, null));
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				new ByteArrayInputStream(DATA), brokenOutput, 3, HASH, null));
	}

	private static byte[] gzip() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(DATA); }
		return output.toByteArray();
	}

	@Test public void decompressedSizeIsAlsoBounded() throws IOException {
		byte[] compressed = gzip();
		try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			VerifiedModelCopy.copy(input, output, 3, HASH, null);
			assertArrayEquals(DATA, output.toByteArray());
		}
		try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			assertThrows(IOException.class, () -> VerifiedModelCopy.copy(input, output, 2, HASH, null));
			assertTrue(output.size() <= 2);
		}
	}

	@Test public void gzipTrailerIsValidatedBeforeSuccess() throws IOException {
		byte[] compressed = gzip();
		compressed[compressed.length - 8] ^= 1;
		try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
					input, new ByteArrayOutputStream(), 3, HASH, null));
		}
	}

	@Test public void retryUsesFreshDigestAndCounters() throws IOException {
		assertThrows(IOException.class, () -> VerifiedModelCopy.copy(
				new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 3, HASH, null));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		VerifiedModelCopy.copy(new ByteArrayInputStream(DATA), output, 3, HASH, null);
		assertArrayEquals(DATA, output.toByteArray());
	}
}
