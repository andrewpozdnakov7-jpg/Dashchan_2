package com.mishiranu.dashchan.content.translation;

import static org.junit.Assert.*;
import org.junit.Test;

public class TranslationCacheKeyTest {
	@Test public void identicalInputSurvivesObjectRecreation() {
		assertEquals(TranslationCacheKey.create("scope", "engine:direction", "subject", "body"),
				TranslationCacheKey.create(new String("scope"), new String("engine:direction"), "subject", "body"));
	}

	@Test public void boundariesCannotCollide() {
		assertNotEquals(TranslationCacheKey.create("ab", "c"), TranslationCacheKey.create("a", "bc"));
		assertNotEquals(TranslationCacheKey.create("a:b", "c"), TranslationCacheKey.create("a", "b:c"));
		assertNotEquals(TranslationCacheKey.create("a"), TranslationCacheKey.create("a", ""));
	}

	@Test public void sourceEngineDirectionRevisionAndScopeAllInvalidate() {
		String[] parts = {"html-v1", "post:chan", "mozilla:en-ru-v1", "11280", "1", "subject", "body"};
		String original = TranslationCacheKey.create(parts);
		for (int i = 0; i < parts.length; i++) {
			String[] changed = parts.clone();
			changed[i] += "changed";
			assertNotEquals("part " + i, original, TranslationCacheKey.create(changed));
		}
	}

	@Test public void unicodeWhitespaceAndMarkupAreNotNormalized() {
		assertNotEquals(TranslationCacheKey.create("\u0410"), TranslationCacheKey.create("A"));
		assertNotEquals(TranslationCacheKey.create("a  b"), TranslationCacheKey.create("a b"));
		assertNotEquals(TranslationCacheKey.create("<b>x</b>"), TranslationCacheKey.create("x"));
		assertEquals(TranslationCacheKey.create((String) null), TranslationCacheKey.create(""));
		assertTrue(TranslationCacheKey.create("private text").matches("[0-9a-f]{64}"));
	}
}
