package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.text.SpannableStringBuilder;
import org.junit.Test;

/** Synthetic digits only; never opens, configures or clears a real protected profile. */
public class LockPinEditingTest {
	@Test public void selectionReplacementUsesTheExistingEditable() {
		SpannableStringBuilder text = new SpannableStringBuilder("123456");
		assertTrue(LockPinEditing.edit(text, 4, 1, "9"));
		assertEquals("1956", text.toString());
	}
	@Test public void thirteenthDigitIsRejectedButSelectionAtLimitCanBeReplaced() {
		SpannableStringBuilder text = new SpannableStringBuilder("123456789012");
		assertFalse(LockPinEditing.edit(text, 12, 12, "3")); assertEquals(12, text.length());
		assertTrue(LockPinEditing.edit(text, 11, 12, "3")); assertEquals("123456789013", text.toString());
	}
	@Test public void deleteRemovesOneSymbolOrTheSelectionAndIsSafeAtStart() {
		SpannableStringBuilder text = new SpannableStringBuilder("123456");
		LockPinEditing.edit(text, 3, 3, null); assertEquals("12456", text.toString());
		LockPinEditing.edit(text, 3, 1, null); assertEquals("156", text.toString());
		LockPinEditing.edit(text, 0, 0, null); assertEquals("156", text.toString());
	}
	@Test public void nonDigitAndMultiCharacterActionsAreNotKeypadInput() {
		SpannableStringBuilder text = new SpannableStringBuilder();
		assertFalse(LockPinEditing.edit(text, 0, 0, "ab"));
		assertFalse(LockPinEditing.edit(text, 0, 0, "x")); assertEquals(0, text.length());
	}
}
