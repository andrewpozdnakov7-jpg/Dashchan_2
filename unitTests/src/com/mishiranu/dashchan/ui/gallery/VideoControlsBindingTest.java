package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoControlsBindingTest {
	@Test public void videoToVideoRetainsPanelsAcrossBothSelectionAndLoadingInterrupts() {
		VideoControlsBinding binding = new VideoControlsBinding();
		binding.select(true, true, true);
		assertTrue(binding.retainsControls());
		assertTrue(binding.visible(false, false, true));
		binding.select(true, false, true); // loadImageVideo interrupts again before decoding.
		assertTrue(binding.retainsControls());
		assertTrue(binding.visible(false, false, true));
		binding.complete();
		assertFalse(binding.retainsControls());
		assertTrue(binding.visible(true, false, true));
	}

	@Test public void hiddenPanelsStayHiddenAndAUserTapStillControlsTheirVisibility() {
		VideoControlsBinding binding = new VideoControlsBinding();
		binding.select(true, true, true);
		assertFalse(binding.visible(false, false, false));
		assertTrue(binding.visible(false, false, true));
		assertFalse(binding.visible(false, false, false));
		binding.complete();
		assertFalse(binding.visible(true, false, false));
	}

	@Test public void photoUnsupportedVideoErrorAndThumbnailOnlySelectionClearRetention() {
		VideoControlsBinding binding = new VideoControlsBinding();
		binding.select(true, true, true);
		binding.select(false, false, true);
		assertFalse(binding.retainsControls());
		assertFalse(binding.visible(false, false, true));
		binding.select(true, false, true);
		assertFalse(binding.retainsControls()); // Photo -> video must not reuse stale panels.
		assertFalse(binding.visible(false, false, true));
		assertTrue(binding.visible(true, false, true));
	}

	@Test public void firstOpenAndLegacyPresentationKeepTheirNormalReadinessGate() {
		VideoControlsBinding binding = new VideoControlsBinding();
		binding.select(true, false, true);
		assertFalse(binding.retainsControls());
		assertFalse(binding.visible(false, false, true));
		assertTrue(binding.visible(true, false, true));
		binding.select(true, true, false);
		assertFalse(binding.retainsControls());
		assertFalse(binding.visible(false, false, true));
	}

	@Test public void closeOrPipCannotLeakRetainedControls() {
		VideoControlsBinding binding = new VideoControlsBinding();
		binding.select(true, true, true);
		assertFalse(binding.visible(false, true, true));
		assertFalse(binding.visible(true, true, true));
		binding.clear();
		assertFalse(binding.retainsControls());
		assertFalse(binding.visible(false, false, true));
	}
}
