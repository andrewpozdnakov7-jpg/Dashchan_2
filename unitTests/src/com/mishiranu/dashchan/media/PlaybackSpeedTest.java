package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import java.util.Locale;
import org.junit.Test;

public class PlaybackSpeedTest {
	@Test public void labelsKeepTheirExistingPrecisionAndUnit() {
		assertEquals("1x", PlaybackSpeed.format(1000));
		assertEquals("1.5x", PlaybackSpeed.format(1500));
		assertEquals("1.25x", PlaybackSpeed.format(1250));
		assertEquals("0.01x", PlaybackSpeed.format(10));
		assertEquals("10x", PlaybackSpeed.format(10000));
	}

	@Test public void labelsKeepTheDecimalPointInAnotherLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);
			assertEquals("1.25x", PlaybackSpeed.format(1250));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test public void customValuesRoundToOnePercentThenClamp() {
		assertEquals(1230, PlaybackSpeed.normalizeCustom(1234));
		assertEquals(1240, PlaybackSpeed.normalizeCustom(1235));
		assertEquals(10, PlaybackSpeed.normalizeCustom(0));
		assertEquals(10, PlaybackSpeed.normalizeCustom(-100));
		assertEquals(10000, PlaybackSpeed.normalizeCustom(10004));
	}

	@Test public void readingCustomValuesOnlyClampsWithoutRounding() {
		assertEquals(1234, PlaybackSpeed.clamp(1234));
		assertEquals(10, PlaybackSpeed.clamp(Integer.MIN_VALUE));
		assertEquals(10000, PlaybackSpeed.clamp(Integer.MAX_VALUE));
	}

	@Test public void rememberedPresetMustBelongToTheConfiguredList() {
		assertEquals(1250, PlaybackSpeed.normalizePreset(1250, new int[] {800, 1250}));
		assertEquals(1000, PlaybackSpeed.normalizePreset(1500, new int[] {800, 1250}));
		assertEquals(1000, PlaybackSpeed.normalizePreset(1500, new int[0]));
	}

	@Test public void readingKeepsOrderAndUnroundedLegacyValues() {
		assertArrayEquals(new int[] {1250, 800, 1011},
				PlaybackSpeed.parsePresets("1250, 800,1250, bad,-1,10001,1011", new int[] {1000}));
	}

	@Test public void fallbackIsIndependentFromTheCallerArray() {
		int[] fallback = {800, 1000};
		int[] result = PlaybackSpeed.parsePresets("bad,0,10001", fallback);
		assertArrayEquals(fallback, result);
		result[0] = 10;
		assertArrayEquals(new int[] {800, 1000}, fallback);
	}

	@Test public void emptyStoredPresetsUseTheExistingFallback() {
		assertArrayEquals(new int[] {800, 1000},
				PlaybackSpeed.parsePresets("", new int[] {800, 1000}));
	}

	@Test public void writingRoundsButKeepsDuplicatesAndOrder() {
		assertEquals("1250,1250,800", PlaybackSpeed.encodePresets(new int[] {1251, 1252, 800}, "1000"));
	}

	@Test public void writingFiltersAfterRounding() {
		assertEquals("10,10000", PlaybackSpeed.encodePresets(new int[] {5, 10004, 10005, -1}, "1000"));
	}

	@Test public void writingAnEmptyOrInvalidListUsesTheExistingFallback() {
		assertEquals("800,1000", PlaybackSpeed.encodePresets(new int[0], "800,1000"));
		assertEquals("800,1000", PlaybackSpeed.encodePresets(new int[] {-10, 10010}, "800,1000"));
	}
}
