package com.mishiranu.dashchan.ui.posting.photo;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditorExportSizingTest {
    @Test public void maximumPreservesOneHundredMegapixels() {
        assertEquals(1, EditorExportSizing.decodeSample(10000, 10000, 0));
        assertArrayEquals(new int[] {10000, 10000}, EditorExportSizing.dimensions(10000, 10000, 0));
        assertArrayEquals(new int[] {16000, 9000}, EditorExportSizing.dimensions(16000, 9000, 0));
    }

    @Test public void onlyExplicitReductionAllowsSubsampling() {
        assertEquals(2, EditorExportSizing.decodeSample(10000, 10000, 4096));
        assertArrayEquals(new int[] {4096, 4096}, EditorExportSizing.dimensions(10000, 10000, 4096));
        assertEquals(8, EditorExportSizing.decodeSample(10000, 10000, 1024));
        assertArrayEquals(new int[] {1024, 576}, EditorExportSizing.dimensions(16000, 9000, 1024));
    }

    @Test public void aSmallOriginalCropKeepsItsDetails() {
        assertEquals(1, EditorExportSizing.decodeSample(1000, 700, 0));
        assertEquals(1, EditorExportSizing.decodeSample(1000, 700, 1024));
        assertArrayEquals(new int[] {1000, 700}, EditorExportSizing.dimensions(1000, 700, 0));
        assertArrayEquals(new int[] {1000, 700}, EditorExportSizing.dimensions(1000, 700, 1024));
    }

    @Test public void orientationAndOddDimensionsHaveExactOutputSizes() {
        assertArrayEquals(new int[] {9000, 16000}, EditorExportSizing.dimensions(9000, 16000, 0));
        assertArrayEquals(new int[] {2304, 4096}, EditorExportSizing.dimensions(9000, 16000, 4096));
        assertEquals(2, EditorExportSizing.decodeSample(8193, 4097, 4096));
        assertArrayEquals(new int[] {4096, 2048}, EditorExportSizing.dimensions(8193, 4097, 4096));
    }

    @Test public void narrowCropsNeverBecomeZeroOrGetUpscaled() {
        assertArrayEquals(new int[] {1, 4096}, EditorExportSizing.dimensions(.01f, 12000, 4096));
        assertEquals(1, EditorExportSizing.decodeSample(80, 60, 4096));
        assertArrayEquals(new int[] {80, 60}, EditorExportSizing.dimensions(80, 60, 4096));
    }

    @Test public void invalidDimensionsAreRejected() {
        for (float width : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            try { EditorExportSizing.dimensions(width, 10, 0); fail(); }
            catch (IllegalArgumentException expected) {}
        }
        try { EditorExportSizing.decodeSample(10, 10, -1); fail(); }
        catch (IllegalArgumentException expected) {}
    }
}
