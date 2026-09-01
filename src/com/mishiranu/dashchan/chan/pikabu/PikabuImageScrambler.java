package com.mishiranu.dashchan.chan.pikabu;

import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

final class PikabuImageScrambler {
	private static final String URI_FRAGMENT_PREFIX = "slooop-pikabu-scrambler-";
	private static final byte[] DATA_SEPARATOR = new byte[] {
			0, 0, 0, 0, 's', 'c', 'r', 'a', 'm', 'b', 'l', 'e', ':'
	};
	private static final int MIME_LENGTH = 20;
	private static final int MIN_SEPARATOR_OFFSET = 10_000;

	private PikabuImageScrambler() {}

	public static Uri mark(Uri uri, String offsetValue) {
		if (uri == null || offsetValue == null) return uri;
		String path = uri.getPath();
		if (path == null || !path.toLowerCase(Locale.US).endsWith(".gif")) return uri;
		try {
			int offset = Integer.parseInt(offsetValue.trim());
			if (offset < 0 || offset > 255) return uri;
			return uri.buildUpon().fragment(URI_FRAGMENT_PREFIX + offset).build();
		} catch (NumberFormatException e) {
			return uri;
		}
	}

	public static int getOffset(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		if (fragment == null || !fragment.startsWith(URI_FRAGMENT_PREFIX)) return -1;
		try {
			int offset = Integer.parseInt(fragment.substring(URI_FRAGMENT_PREFIX.length()));
			return offset >= 0 && offset <= 255 ? offset : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static Uri getRequestUri(Uri uri) {
		return getOffset(uri) >= 0 ? uri.buildUpon().fragment(null).build() : uri;
	}

	public static byte[] decode(byte[] source, int offset) {
		if (source == null || offset < 0 || offset > 255) return null;
		int separator = findSeparator(source);
		int header = separator >= 0 ? separator + DATA_SEPARATOR.length : -1;
		if (header < 0 || header + MIME_LENGTH + 1 > source.length) return null;

		int mimeEnd = header;
		while (mimeEnd < header + MIME_LENGTH && source[mimeEnd] != 0) mimeEnd++;
		String mimeType = new String(source, header, mimeEnd - header, StandardCharsets.US_ASCII);
		if (!mimeType.startsWith("image/")) return null;

		int mode = source[header + MIME_LENGTH] & 0xff;
		byte[] decoded = Arrays.copyOfRange(source, header + MIME_LENGTH + 1, source.length);
		if (mode == 1) {
			for (int i = 0; i < decoded.length; i++) decoded[i] = (byte) ((decoded[i] & 0xff) - offset);
		} else if (mode != 0) {
			return null;
		}
		return decoded.length > 0 ? decoded : null;
	}

	private static int findSeparator(byte[] source) {
		for (int i = MIN_SEPARATOR_OFFSET; i <= source.length - DATA_SEPARATOR.length; i++) {
			boolean matches = true;
			for (int j = 0; j < DATA_SEPARATOR.length; j++) {
				if (source[i + j] != DATA_SEPARATOR[j]) {
					matches = false;
					break;
				}
			}
			if (matches) return i;
		}
		return -1;
	}

}
