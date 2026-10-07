package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import org.junit.Test;

/** The F-Droid bridge must never depend on the optional editor or its resources. */
public class PhotoEditorBridgeTest {
	@Test public void experimentalEditorIsUnavailable() {
		assertFalse(PhotoEditorBridge.isAvailable());
	}

	@Test public void experimentalSettingHasNoResourceIds() {
		assertEquals(0, PhotoEditorBridge.getTitleResId());
		assertEquals(0, PhotoEditorBridge.getSummaryResId());
	}

	@Test public void routingAlwaysFallsBackWithoutReadingPreferences() {
		assertNull(PhotoEditorBridge.createIntent(null, "fixture-hash", "fixture.png", 3));
	}
}
