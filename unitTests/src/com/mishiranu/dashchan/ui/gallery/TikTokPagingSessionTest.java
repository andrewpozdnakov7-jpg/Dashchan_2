package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import org.junit.Test;

public class TikTokPagingSessionTest {
	private static final int ALL = 0, VIDEO = 1, IMAGE = 2;

	@Test public void excludedOpeningDoesNotRearmOnLaterMatchingVideos() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, VIDEO);
		assertFalse(session.selectItem(false)); // Image -> video -> image -> video.
		assertFalse(session.selectItem(true));
		assertFalse(session.selectItem(false));
		assertFalse(session.selectItem(true));
		assertTrue(session.snapshot().preferenceEnabled);
	}

	@Test public void unchangedPreferencesOnResumeDoNotRearmOrdinaryBrowsing() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, VIDEO);
		assertFalse(session.selectItem(false));
		session.syncPreferences(true, VIDEO);
		assertFalse(session.selectItem(true));
		session.syncPreferences(true, VIDEO);
		assertFalse(session.selectItem(true));
	}

	@Test public void savedSessionRemainsOrdinaryWhenRestoredOnAVideo() {
		TikTokPagingSession original = new TikTokPagingSession();
		original.syncPreferences(true, VIDEO);
		original.selectItem(false);
		original.selectItem(true);
		TikTokPagingSession restored = new TikTokPagingSession(original.snapshot());
		restored.syncPreferences(true, VIDEO);
		assertFalse(restored.selectItem(true)); // Rotation, process restoration or PiP return.
	}

	@Test public void matchingOpeningKeepsSavedTikTokPreference() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, VIDEO);
		assertTrue(session.selectItem(true));
		assertTrue(session.selectItem(true));
		session.syncPreferences(true, VIDEO);
		assertTrue(session.selectItem(true));
	}

	@Test public void allMediaFilterKeepsVerticalPagingForBothImagesAndVideos() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, ALL);
		assertTrue(session.selectItem(true));
		assertTrue(session.selectItem(true));
		assertTrue(session.selectItem(true));
	}

	@Test public void imageOnlyFilterCannotRearmAfterAnExcludedVideo() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, IMAGE);
		assertTrue(session.selectItem(true));
		assertFalse(session.selectItem(false));
		assertFalse(session.selectItem(true));
	}

	@Test public void explicitEnableAndDisableOverrideTheCurrentSession() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, VIDEO);
		assertFalse(session.selectItem(false));
		session.selectMode(true, VIDEO);
		assertTrue(session.selectItem(true));
		session.selectMode(false, VIDEO);
		assertFalse(session.selectItem(true));
		session.syncPreferences(false, VIDEO);
		assertFalse(session.selectItem(true));
	}

	@Test public void aNewExplicitFilterResolvesAgainstItsDestination() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(true, VIDEO);
		session.selectItem(false);
		session.selectMode(true, IMAGE);
		assertTrue(session.selectItem(true)); // Newly selected image, not the old video.
	}

	@Test public void latestExternalPreferenceWinsOverAStaleSnapshot() {
		TikTokPagingSession original = new TikTokPagingSession();
		original.syncPreferences(true, VIDEO);
		original.selectItem(true);
		TikTokPagingSession restored = new TikTokPagingSession(original.snapshot());
		restored.syncPreferences(false, VIDEO);
		assertFalse(restored.selectItem(true));
		restored.syncPreferences(true, VIDEO);
		assertTrue(restored.selectItem(true));
	}

	@Test public void disabledPreferenceNeverActivatesOnMediaChanges() {
		TikTokPagingSession session = new TikTokPagingSession();
		session.syncPreferences(false, ALL);
		assertFalse(session.selectItem(true));
		assertFalse(session.selectItem(false));
		assertFalse(session.selectItem(true));
	}
}
