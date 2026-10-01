package com.mishiranu.dashchan.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuditPreferenceKeysTest {
	@Test public void knownVisualKeysUseFixedLabels() {
		assertEquals("catalog_sort", AuditPreferenceKeys.label("catalog_sort"));
		assertEquals("popup_colors", AuditPreferenceKeys.label("popup_background"));
		assertEquals("text_scale", AuditPreferenceKeys.label("subject_text_scale"));
	}
	@Test public void unknownAndDynamicKeysAreNeverReturned() {
		assertEquals("other", AuditPreferenceKeys.label(null));
		assertEquals("other", AuditPreferenceKeys.label("https://example.invalid/private"));
		assertEquals("other", AuditPreferenceKeys.label("secret-cookie-key"));
		assertEquals("other", AuditPreferenceKeys.label("catalog_sort/user-value"));
	}
}
