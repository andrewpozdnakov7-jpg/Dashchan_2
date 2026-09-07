package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpResponse;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PikabuImageScrambler {
	private static final String URI_FRAGMENT_PREFIX = "slooop-cache-pikabu-scrambler-v2-";
	private static final byte[] DATA_SEPARATOR = new byte[] {
			0, 0, 0, 0, 's', 'c', 'r', 'a', 'm', 'b', 'l', 'e', ':'
	};
	private static final int MIME_LENGTH = 20;
	private static final int MIN_SEPARATOR_OFFSET = 10_000;
	private static final long MAX_CONTAINER_SIZE = 64L * 1024L * 1024L;
	private static final int[] SEPARATOR_PREFIX = buildPrefixTable(DATA_SEPARATOR);

	private PikabuImageScrambler() {}

	public static Uri mark(Uri uri, String offsetValue) {
		if (uri == null || offsetValue == null) return uri;
		String path = uri.getPath();
		if (path == null || !path.toLowerCase(Locale.US).endsWith(".gif")) return uri;
		try {
			// Pikabu supplies the story or comment identifier here, not an unsigned byte.
			long offset = Long.parseLong(offsetValue.trim());
			if (offset < 0) return uri;
			return uri.buildUpon().fragment(URI_FRAGMENT_PREFIX + offset).build();
		} catch (NumberFormatException e) {
			return uri;
		}
	}

	public static long getOffset(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		if (fragment == null || !fragment.startsWith(URI_FRAGMENT_PREFIX)) return -1;
		try {
			long offset = Long.parseLong(fragment.substring(URI_FRAGMENT_PREFIX.length()));
			return offset >= 0 ? offset : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static Uri getRequestUri(Uri uri) {
		return getOffset(uri) >= 0 ? uri.buildUpon().fragment(null).build() : uri;
	}

	public static HttpResponse decode(HttpResponse response, long offset)
			throws HttpException, InvalidResponseException {
		if (response == null || offset < 0 || response.getLength() > MAX_CONTAINER_SIZE) {
			if (response != null) response.cleanupAndDisconnect();
			throw new InvalidResponseException();
		}
		try {
			LimitedInputStream input = new LimitedInputStream(new BufferedInputStream(response.open()),
					MAX_CONTAINER_SIZE);
			int matched = 0;
			while (true) {
				int value = input.read();
				if (value < 0) throw new InvalidResponseException();
				long index = input.getCount() - 1L;
				if (index < MIN_SEPARATOR_OFFSET) {
					matched = 0;
					continue;
				}
				while (matched > 0 && value != (DATA_SEPARATOR[matched] & 0xff)) {
					matched = SEPARATOR_PREFIX[matched - 1];
				}
				if (value == (DATA_SEPARATOR[matched] & 0xff)) matched++;
				if (matched == DATA_SEPARATOR.length) break;
			}

			byte[] header = new byte[MIME_LENGTH + 1];
			int headerRead = 0;
			while (headerRead < header.length) {
				int count = input.read(header, headerRead, header.length - headerRead);
				if (count < 0) throw new InvalidResponseException();
				headerRead += count;
			}
			int mimeEnd = 0;
			while (mimeEnd < MIME_LENGTH && header[mimeEnd] != 0) mimeEnd++;
			String mimeType = new String(header, 0, mimeEnd, StandardCharsets.US_ASCII);
			if (!mimeType.startsWith("image/")) throw new InvalidResponseException();
			int mode = header[MIME_LENGTH] & 0xff;
			if (mode != 0 && mode != 1) throw new InvalidResponseException();
			int firstByte = input.read();
			if (firstByte < 0) throw new InvalidResponseException();
			return new HttpResponse(new DecodingInputStream(input, response, mode, offset, firstByte));
		} catch (InvalidResponseException e) {
			response.cleanupAndDisconnect();
			throw e;
		} catch (HttpException e) {
			response.cleanupAndDisconnect();
			throw e;
		} catch (IOException e) {
			throw response.fail(e);
		}
	}

	private static int[] buildPrefixTable(byte[] pattern) {
		int[] table = new int[pattern.length];
		for (int i = 1, length = 0; i < pattern.length;) {
			if (pattern[i] == pattern[length]) {
				table[i++] = ++length;
			} else if (length > 0) {
				length = table[length - 1];
			} else {
				table[i++] = 0;
			}
		}
		return table;
	}

	private static class LimitedInputStream extends FilterInputStream {
		private final long maximum;
		private long count;

		public LimitedInputStream(InputStream input, long maximum) {
			super(input);
			this.maximum = maximum;
		}

		public long getCount() {
			return count;
		}

		@Override
		public int read() throws IOException {
			if (count >= maximum) {
				if (super.read() < 0) return -1;
				throw new IOException("Pikabu image container is too large");
			}
			int value = super.read();
			if (value >= 0) count++;
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) return 0;
			long remaining = maximum - count;
			if (remaining <= 0) return read();
			int read = super.read(buffer, offset, (int) Math.min(length, remaining));
			if (read > 0) count += read;
			return read;
		}
	}

	private static class DecodingInputStream extends FilterInputStream {
		private final HttpResponse response;
		private final int mode;
		private final long offset;
		private int firstByte;

		public DecodingInputStream(InputStream input, HttpResponse response, int mode, long offset, int firstByte) {
			super(input);
			this.response = response;
			this.mode = mode;
			this.offset = offset;
			this.firstByte = firstByte;
		}

		private int decode(int value) {
			return mode == 1 ? (int) (((value & 0xffL) - offset) & 0xffL) : value;
		}

		@Override
		public int read() throws IOException {
			if (firstByte >= 0) {
				int value = firstByte;
				firstByte = -1;
				return decode(value);
			}
			int value = super.read();
			return value >= 0 ? decode(value) : -1;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) return 0;
			int start = offset;
			if (firstByte >= 0) {
				buffer[offset++] = (byte) read();
				length--;
				if (length == 0) return 1;
			}
			int count = super.read(buffer, offset, length);
			if (count < 0) return offset > start ? offset - start : -1;
			if (mode == 1) {
				for (int i = offset; i < offset + count; i++) buffer[i] = (byte) decode(buffer[i]);
			}
			return offset - start + count;
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				response.cleanupAndDisconnect();
			}
		}
	}

}
