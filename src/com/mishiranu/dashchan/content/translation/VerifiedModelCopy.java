package com.mishiranu.dashchan.content.translation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongConsumer;

final class VerifiedModelCopy {
	private VerifiedModelCopy() {}

	// Does not close either stream. The caller owns staging and publishes only after success.
	static void copy(InputStream input, OutputStream output, long expectedSize, String expectedSha256,
			LongConsumer progress) throws IOException {
		if (expectedSize < 0) throw new IOException("Invalid language package size");
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 unavailable", e);
		}
		byte[] buffer = new byte[65536];
		long current = 0;
		while (true) {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedIOException("Language package operation cancelled");
			}
			long remaining = expectedSize - current;
			// Probe at most one byte past the limit; never write that byte to disk.
			int requested = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
			int count = input.read(buffer, 0, requested);
			if (count < 0) break;
			if (count == 0) continue;
			if (count > remaining) throw new IOException("Language package exceeds expected size");
			output.write(buffer, 0, count);
			digest.update(buffer, 0, count);
			current += count;
			if (progress != null) progress.accept(current);
		}
		if (current != expectedSize) throw new IOException("Unexpected language package size");
		StringBuilder hash = new StringBuilder(64);
		for (byte value : digest.digest()) {
			hash.append(Character.forDigit((value & 0xff) >>> 4, 16));
			hash.append(Character.forDigit(value & 0xf, 16));
		}
		if (!hash.toString().equals(expectedSha256)) {
			throw new IOException("Language package checksum mismatch");
		}
	}
}
