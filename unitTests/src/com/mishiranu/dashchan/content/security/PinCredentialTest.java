package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class PinCredentialTest {
	@Test public void sixToTwelveAsciiDigitsOnly() {
		assertFalse(PinCredential.isValid(null));
		for (String invalid : new String[]{"", "12345", "1234567890123", "12345a", "１２３４５６", "123 456"}) {
			assertFalse(PinCredential.isValid(invalid.toCharArray()));
		}
		assertTrue(PinCredential.isValid("123456".toCharArray()));
		assertTrue(PinCredential.isValid("123456789012".toCharArray()));
	}
	@Test public void saltedDerivationIsRepeatableButDifferentForAnotherPinOrSalt() throws Exception {
		byte[] salt = PinCredential.salt(), otherSalt = PinCredential.salt();
		byte[] key = PinCredential.derive("123456".toCharArray(), salt);
		assertTrue(PinCredential.equal(key, PinCredential.derive("123456".toCharArray(), salt)));
		assertFalse(PinCredential.equal(key, PinCredential.derive("654321".toCharArray(), salt)));
		assertFalse(PinCredential.equal(key, PinCredential.derive("123456".toCharArray(), otherSalt)));
		assertEquals(32, key.length);
	}
	@Test public void clearOverwritesInput() {
		char[] pin = "123456".toCharArray(); PinCredential.clear(pin);
		assertArrayEquals(new char[6], pin);
	}
}
