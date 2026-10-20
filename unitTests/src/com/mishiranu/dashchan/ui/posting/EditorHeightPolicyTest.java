package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import org.junit.Test;

public class EditorHeightPolicyTest {
	@Test public void dockedKeyboardEditorReservesOnlyPrimaryActionWithSixDpGaps() {
		// 52dp formatting chrome; 6dp gap + original 56dp primary + 6dp lower padding.
		assertEquals(294, EditorHeightPolicy.keyboardInputHeight(420, 6, 52 + 68, 92));
		assertEquals(714, EditorHeightPolicy.keyboardInputHeight(840, 6, 52 + 68, 92));
		// Attachments and the hidden utility row do not enter this calculation.
		assertEquals(346, EditorHeightPolicy.keyboardInputHeight(420, 6, 68, 92));
	}
	@Test public void dockedKeyboardEditorRetainsMinimumInTinyWindow() {
		assertEquals(92, EditorHeightPolicy.keyboardInputHeight(100, 100, 52, 92));
		assertEquals(420, EditorHeightPolicy.keyboardInputHeight(420, -10, -1, 92));
		assertEquals(1, EditorHeightPolicy.keyboardInputHeight(-1, -1, -1, -1));
	}
	@Test public void keyboardViewportUsesMeasuredToolbarOverlapRatherThanShorteningForSubject() {
		// 115/05 geometry at density 4: 1472px viewport, 52dp formatting row,
		// Top6dp + primary68dp, plus 8dp actual toolbar overlap over the old 56dp inset.
		assertEquals(936, EditorHeightPolicy.keyboardInputHeight(1472, 24 + 32, 208 + 272, 368));
		assertEquals(968, EditorHeightPolicy.keyboardInputHeight(1472, 24, 208 + 272, 368));
	}
	@Test public void adaptiveEditorRetainsEmptyFormHeightWithoutAttachments() {
		assertEquals(800, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 200, 0, 100, false));
	}
	@Test public void adaptiveEditorShrinksOnlyBySmallAttachmentHeight() {
		assertEquals(680, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 320, 120, 100, false));
	}
	@Test public void attachmentCountCannotShrinkAdaptiveEditorBeyondTwentyPercent() {
		assertEquals(640, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 800, 600, 100, false));
		assertEquals(640, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 3200, 3000, 100, false));
		assertEquals(643, EditorHeightPolicy.adaptiveAutomaticHeight(1003, 3200, 3000, 100, false));
	}
	@Test public void removingAttachmentsRestoresAdaptiveEditorHeight() {
		assertEquals(640, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 3200, 3000, 100, false));
		assertEquals(800, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 200, 0, 100, false));
	}
	@Test public void keyboardVisibleEditorDoesNotLoseHeightToAnyAttachmentCount() {
		int emptyHeight = EditorHeightPolicy.adaptiveAutomaticHeight(420, 160, 0, 96, true);
		assertEquals(260, emptyHeight);
		for (int attachments : new int[] {0, 50, 450, 3000}) {
			assertEquals(emptyHeight,
					EditorHeightPolicy.adaptiveAutomaticHeight(420, 160 + attachments, attachments, 96, true));
		}
	}
	@Test public void keyboardOnlyReducesAvailableViewportAndRestoresHiddenKeyboardPolicy() {
		assertEquals(640, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 800, 600, 96, false));
		assertEquals(300, EditorHeightPolicy.adaptiveAutomaticHeight(500, 800, 600, 96, true));
		assertEquals(800, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 800, 600, 96, true));
		assertEquals(640, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 800, 600, 96, false));
	}
	@Test public void adaptiveEditorRetainsMinimumInTinyViewports() {
		assertEquals(96, EditorHeightPolicy.adaptiveAutomaticHeight(120, 660, 500, 96, true));
		assertEquals(96, EditorHeightPolicy.adaptiveAutomaticHeight(120, 660, 500, 96, false));
	}
	@Test public void adaptiveEditorClampsInvalidGeometry() {
		assertEquals(800, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 200, -1, 100, false));
		assertEquals(800, EditorHeightPolicy.adaptiveAutomaticHeight(1000, 200, 2000, 100, false));
		assertEquals(1, EditorHeightPolicy.adaptiveAutomaticHeight(-1, -1, -1, -1, false));
		assertEquals(1, EditorHeightPolicy.adaptiveAutomaticHeight(-1, -1, -1, -1, true));
	}
	@Test public void automaticWithoutKeyboardPreservesAvailableSpacePolicy() {
		EditorHeightPolicy.Bounds bounds = bounds(EditorHeightPolicy.Mode.AUTOMATIC, false, 400, 140, 8);
		assertTrue(bounds.pixels);
		assertEquals(260, bounds.minimum);
		assertEquals(260, bounds.maximum);
	}
	@Test public void automaticWithKeyboardUsesOriginalFourToNineLines() {
		EditorHeightPolicy.Bounds bounds = bounds(EditorHeightPolicy.Mode.AUTOMATIC, true, 400, 140, 8);
		assertFalse(bounds.pixels);
		assertEquals(4, bounds.minimum);
		assertEquals(9, bounds.maximum);
	}
	@Test public void compactNeverFillsUnusedViewport() {
		EditorHeightPolicy.Bounds bounds = bounds(EditorHeightPolicy.Mode.COMPACT, false, 700, 0, 8);
		assertFalse(bounds.pixels);
		assertEquals(4, bounds.minimum);
		assertEquals(9, bounds.maximum);
	}
	@Test public void expandedFitsAboveKeyboardInShortWindow() {
		EditorHeightPolicy.Bounds bounds = bounds(EditorHeightPolicy.Mode.EXPANDED, true, 100, 0, 8);
		assertEquals(4, bounds.minimum);
		assertEquals(4, bounds.maximum);
	}
	@Test public void fixedHeightIsIndependentOfOtherFormFields() {
		EditorHeightPolicy.Bounds first = bounds(EditorHeightPolicy.Mode.FIXED, false, 400, 0, 7);
		EditorHeightPolicy.Bounds second = bounds(EditorHeightPolicy.Mode.FIXED, false, 400, 300, 7);
		assertEquals(7, first.minimum);
		assertEquals(first.minimum, first.maximum);
		assertEquals(first.minimum, second.minimum);
		assertEquals(first.maximum, second.maximum);
	}
	@Test public void fixedHeightIsClampedAndStillHasOneLineInTinyViewport() {
		assertEquals(20, bounds(EditorHeightPolicy.Mode.FIXED, false, 700, 0, 100).maximum);
		assertEquals(2, bounds(EditorHeightPolicy.Mode.FIXED, false, 700, 0, -100).minimum);
		assertEquals(1, bounds(EditorHeightPolicy.Mode.FIXED, true, 10, 0, 20).maximum);
	}
	private static EditorHeightPolicy.Bounds bounds(EditorHeightPolicy.Mode mode, boolean keyboard,
			int available, int other, int lines) {
		return EditorHeightPolicy.calculate(mode, keyboard, available, other, 10, 20, lines);
	}
}
