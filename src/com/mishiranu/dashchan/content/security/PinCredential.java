package com.mishiranu.dashchan.content.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** No plaintext PINs, reversible PINs, or fast unsalted PIN hashes. */
public final class PinCredential {
	public static final int ITERATIONS = 600_000;
	private PinCredential() {}

	public static boolean isValid(char[] pin) {
		if (pin == null || pin.length < 6 || pin.length > 12) return false;
		for (char digit : pin) if (digit < '0' || digit > '9') return false;
		return true;
	}

	public static byte[] salt() {
		byte[] salt = new byte[32];
		new SecureRandom().nextBytes(salt);
		return salt;
	}

	public static byte[] derive(char[] pin, byte[] salt) throws GeneralSecurityException {
		if (!isValid(pin) || salt.length != 32) throw new GeneralSecurityException("Invalid credential");
		PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, 256);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	public static boolean equal(byte[] first, byte[] second) {
		return MessageDigest.isEqual(first, second);
	}

	public static void clear(char[] pin) {
		if (pin != null) Arrays.fill(pin, '\0');
	}
}
