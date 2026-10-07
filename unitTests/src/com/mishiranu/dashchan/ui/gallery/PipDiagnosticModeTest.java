package com.mishiranu.dashchan.ui.gallery;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure publication policy: does not simulate SystemUI or claim a rotation fix. */
public class PipDiagnosticModeTest {
	@Test public void missingOrUnknownPreferenceRetainsNormalBehavior() {
		assertSame(PipDiagnosticMode.NORMAL, PipDiagnosticMode.fromPreference(null));
		assertSame(PipDiagnosticMode.NORMAL, PipDiagnosticMode.fromPreference(""));
		assertSame(PipDiagnosticMode.NORMAL, PipDiagnosticMode.fromPreference("unknown"));
		assertSame(PipDiagnosticMode.NORMAL, PipDiagnosticMode.fromPreference("normal"));
	}

	@Test public void preferenceValuesAndProbeIdsAreDistinctAndStable() {
		String[] values = {"normal", "no_hint", "entry_hint", "layout_hint", "no_seamless_resize"};
		for (int i = 0; i < values.length; i++) {
			PipDiagnosticMode mode = PipDiagnosticMode.fromPreference(values[i]);
			assertEquals(values[i], mode.preferenceValue); assertEquals(i - 1, mode.probeId);
		}
	}

	@Test public void normalAllowsExistingHintAndUpdatesDuringBothEntryAndExit() {
		assertFalse(PipDiagnosticMode.NORMAL.isDiagnostic());
		assertTrue(PipDiagnosticMode.NORMAL.allowsHintAtEntry());
		assertTrue(PipDiagnosticMode.NORMAL.allowsParameterUpdate(false));
		assertTrue(PipDiagnosticMode.NORMAL.allowsParameterUpdate(true));
	}

	@Test public void modeZeroNeverUsesAHintOrSubsequentParameterUpdate() {
		assertTrue(PipDiagnosticMode.NO_HINT.isDiagnostic());
		assertFalse(PipDiagnosticMode.NO_HINT.allowsHintAtEntry());
		assertFalse(PipDiagnosticMode.NO_HINT.allowsParameterUpdate(false));
		assertFalse(PipDiagnosticMode.NO_HINT.allowsParameterUpdate(true));
	}

	@Test public void modeOneUsesOnlyAnEntryHint() {
		assertTrue(PipDiagnosticMode.ENTRY_HINT.isDiagnostic());
		assertTrue(PipDiagnosticMode.ENTRY_HINT.allowsHintAtEntry());
		assertFalse(PipDiagnosticMode.ENTRY_HINT.allowsParameterUpdate(false));
		assertFalse(PipDiagnosticMode.ENTRY_HINT.allowsParameterUpdate(true));
	}

	@Test public void modeTwoAllowsHintUpdatesOnlyInsidePip() {
		assertTrue(PipDiagnosticMode.LAYOUT_HINT.isDiagnostic());
		assertTrue(PipDiagnosticMode.LAYOUT_HINT.allowsHintAtEntry());
		assertTrue(PipDiagnosticMode.LAYOUT_HINT.allowsParameterUpdate(true));
		assertFalse(PipDiagnosticMode.LAYOUT_HINT.allowsParameterUpdate(false));
	}

	@Test public void modeThreeMatchesModeZeroExceptForSeamlessResize() {
		PipDiagnosticMode mode = PipDiagnosticMode.NO_SEAMLESS_RESIZE;
		assertTrue(mode.isDiagnostic());
		assertEquals(PipDiagnosticMode.NO_HINT.allowsHintAtEntry(), mode.allowsHintAtEntry());
		assertEquals(PipDiagnosticMode.NO_HINT.allowsParameterUpdate(false), mode.allowsParameterUpdate(false));
		assertEquals(PipDiagnosticMode.NO_HINT.allowsParameterUpdate(true), mode.allowsParameterUpdate(true));
		assertFalse(mode.isSeamlessResizeEnabled());
		for (PipDiagnosticMode other : PipDiagnosticMode.values()) {
			if (other != mode) assertTrue(other.isSeamlessResizeEnabled());
		}
	}
}
