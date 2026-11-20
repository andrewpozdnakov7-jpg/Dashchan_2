package com.mishiranu.dashchan.media.compression;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Converter input boundary, independent of codec/device/UI. Originals are never modified. */
final class VideoInputNormalizer {
	private static final int MAX_BOXES = 100_000, MAX_TEXT_TAIL = 64 * 1024;
	private static final HashSet<File> LEASED = new HashSet<>();
	private VideoInputNormalizer() {}
	static final class Inspection {
		final long originalBytes, readableBytes;
		final String reason;
		Inspection(long originalBytes, long readableBytes, String reason) {
			this.originalBytes = originalBytes; this.readableBytes = readableBytes; this.reason = reason;
		}
		boolean needsCopy() { return readableBytes < originalBytes; }
	}
	static final class Input implements Closeable {
		final File file;
		final Inspection inspection;
		final boolean temporary;
		private boolean closed;
		Input(File file, Inspection inspection, boolean temporary) {
			this.file = file; this.inspection = inspection; this.temporary = temporary;
		}
		@Override public synchronized void close() {
			if (closed || !temporary) return;
			closed = true;
			synchronized (LEASED) { file.delete(); LEASED.remove(file); }
		}
	}
	private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
		if (cancelled.getAsBoolean()) throw new InterruptedIOException("Input preparation cancelled");
	}
	private static boolean startType(int type) {
		return type == 0x66747970 || type == 0x6d6f6f76 || type == 0x6d646174 || type == 0x66726565
				|| type == 0x77696465 || type == 0x736b6970 || type == 0x73747970;
	}
	private static boolean knownType(int type) {
		return startType(type) || type == 0x6d6f6f66 || type == 0x73696478 || type == 0x6d667261
				|| type == 0x75756964 || type == 0x6d657461 || type == 0x75647461 || type == 0x656d7367 || type == 0x70726674;
	}
	/** Text garbage outside complete boxes includes unique-hash suffixes, not truncated binary box data. */
	private static Inspection tail(RandomAccessFile input, long length, long position,
			boolean hasMovie, boolean hasMedia, boolean possibleBox, BooleanSupplier cancelled) throws IOException {
		long count = length - position;
		if (!hasMovie || !hasMedia || possibleBox || count <= 0 || count > MAX_TEXT_TAIL)
			return new Inspection(length, length, "unproven_tail");
		input.seek(position);
		byte[] buffer = new byte[(int) Math.min(count, 4096)];
		for (long remaining = count; remaining > 0;) {
			checkCancelled(cancelled);
			int read = (int) Math.min(remaining, buffer.length); input.readFully(buffer, 0, read);
			for (int i = 0; i < read; i++) {
				int value = buffer[i] & 0xff;
				if (!(value >= 0x20 && value <= 0x7e || value == '\t' || value == '\r' || value == '\n'))
					return new Inspection(length, length, "non_text_tail");
			}
			remaining -= read;
		}
		return new Inspection(length, position, "trailing_text_after_complete_boxes");
	}
	static Inspection inspect(File file, BooleanSupplier cancelled) throws IOException {
		checkCancelled(cancelled);
		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			long length = input.length();
			if (length < 8) return new Inspection(length, length, "short_input");
			input.seek(4);
			if (!startType(input.readInt())) return new Inspection(length, length, "other_container");
			long position = 0; int boxes = 0; boolean movie = false, media = false;
			while (position < length && boxes++ < MAX_BOXES) {
				checkCancelled(cancelled);
				long remaining = length - position;
				if (remaining < 8) return tail(input, length, position, movie, media, false, cancelled);
				input.seek(position);
				long size = input.readInt() & 0xffffffffL; int type = input.readInt(), header = 8;
				if (size == 1) {
					if (remaining < 16) return new Inspection(length, length, "partial_extended_header");
					size = input.readLong(); header = 16;
				} else if (size == 0) size = remaining;
				if (type == 0x75756964) header += 16;
				if (size < header || size > remaining)
					return tail(input, length, position, movie, media, knownType(type), cancelled);
				if (type == 0x66747970 && size < header + 8) return new Inspection(length, length, "invalid_ftyp");
				if (type == 0x6d6f6f76) movie = true;
				if (type == 0x6d646174 && size > header) media = true;
				position += size;
			}
			return new Inspection(length, length, position == length ? "complete_container" : "box_scan_limit");
		}
	}
	private static void cleanupLocked(File directory) throws IOException {
		// Only abandoned files created by this normalizer, never the original/draft cache.
		File[] stale = directory.listFiles();
		if (stale != null) for (File file : stale) {
			if (file.getName().matches("normalize-[0-9a-f-]{36}\\.mp4") && !LEASED.contains(file)
					&& file.isFile() && file.getCanonicalFile().equals(file)) file.delete();
		}
	}
	static void cleanup(File directory) throws IOException {
		directory = directory.getCanonicalFile();
		synchronized (LEASED) { cleanupLocked(directory); }
	}
	private static File createTemporary(File directory) throws IOException {
		directory = directory.getCanonicalFile();
		if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Input cache unavailable");
		synchronized (LEASED) {
			cleanupLocked(directory);
			File result = new File(directory, "normalize-" + UUID.randomUUID() + ".mp4");
			if (!result.createNewFile()) throw new IOException("Input cache collision");
			LEASED.add(result); return result;
		}
	}
	static Input prepare(File original, File directory, BooleanSupplier cancelled) throws IOException {
		Inspection inspection = inspect(original, cancelled);
		if (!inspection.needsCopy()) return new Input(original, inspection, false);
		File file = createTemporary(directory);
		Input result = new Input(file, inspection, true);
		boolean accepted = false;
		try {
			try (FileInputStream input = new FileInputStream(original); FileOutputStream output = new FileOutputStream(file)) {
				byte[] buffer = new byte[64 * 1024]; long remaining = inspection.readableBytes;
				while (remaining > 0) {
					checkCancelled(cancelled);
					int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
					if (read <= 0) throw new IOException("Input changed during preparation");
					output.write(buffer, 0, read); remaining -= read;
				}
			}
			checkCancelled(cancelled);
			if (original.length() != inspection.originalBytes || file.length() != inspection.readableBytes)
				throw new IOException("Input changed during preparation");
			accepted = true; return result;
		} finally { if (!accepted) result.close(); }
	}
}
