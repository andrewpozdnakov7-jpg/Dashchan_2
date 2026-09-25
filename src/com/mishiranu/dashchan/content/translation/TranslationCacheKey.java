package com.mishiranu.dashchan.content.translation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Length-prefixed content identity: no post numbers, credentials or mutable View state. */
public final class TranslationCacheKey {
	private TranslationCacheKey() {}

	public static String create(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : parts) {
				byte[] bytes = (part != null ? part : "").getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
				digest.update(bytes);
			}
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest.digest()) {
				result.append(Character.forDigit((value >>> 4) & 15, 16));
				result.append(Character.forDigit(value & 15, 16));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
