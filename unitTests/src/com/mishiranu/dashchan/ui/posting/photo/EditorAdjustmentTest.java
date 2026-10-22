package com.mishiranu.dashchan.ui.posting.photo;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditorAdjustmentTest {
    @Test public void versionOneStorageIndicesAndUiRangesRemainStable() {
        EditorDocument.Adjustment[] expected = {EditorDocument.Adjustment.BRIGHTNESS,
                EditorDocument.Adjustment.CONTRAST, EditorDocument.Adjustment.SATURATION,
                EditorDocument.Adjustment.WARMTH, EditorDocument.Adjustment.SHADOWS,
                EditorDocument.Adjustment.HIGHLIGHTS, EditorDocument.Adjustment.SHARPNESS};
        assertEquals(7, EditorDocument.Adjustment.COUNT);
        assertEquals(expected.length, EditorDocument.Adjustment.values().length);
        boolean[] seen = new boolean[expected.length];
        for (int index = 0; index < expected.length; index++) {
            EditorDocument.Adjustment adjustment = expected[index];
            assertEquals(index, adjustment.storageIndex);
            assertFalse(seen[adjustment.storageIndex]);
            seen[adjustment.storageIndex] = true;
            assertEquals(adjustment == EditorDocument.Adjustment.SHARPNESS ? 0 : -100, adjustment.uiMinimum);
            assertEquals(100, adjustment.uiMaximum);
        }
    }

    @Test public void selectedAdjustmentKeepsTheOldClampingRule() {
        assertEquals(0, EditorDocument.Adjustment.clampStorageIndex(Integer.MIN_VALUE));
        assertEquals(6, EditorDocument.Adjustment.clampStorageIndex(Integer.MAX_VALUE));
        for (int index = 0; index < 7; index++) {
            assertEquals(index, EditorDocument.Adjustment.clampStorageIndex(index));
        }
    }
}
