package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.R;
import org.junit.Test;

/** Shared availability/metadata; real Intent and preference behavior is covered on Android. */
public class PhotoEditorBridgeTest {
	@Test public void experimentalEditorIsAvailableInBothDistributions() {
		assertTrue(PhotoEditorBridge.isAvailable());
	}

	@Test public void experimentalSettingUsesSharedResourceIds() {
		assertEquals(R.string.new_photo_editor, PhotoEditorBridge.getTitleResId());
		assertEquals(R.string.new_photo_editor__summary, PhotoEditorBridge.getSummaryResId());
	}

}
