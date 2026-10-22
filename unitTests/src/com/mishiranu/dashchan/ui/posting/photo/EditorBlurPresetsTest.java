package com.mishiranu.dashchan.ui.posting.photo;

import static org.junit.Assert.*;
import org.junit.Test;

public class EditorBlurPresetsTest {
    @Test public void presetsAreOrderedAndNormalizedToSourceSize() {
        assertEquals(.006f, EditorBlurPresets.size(0), 0);
        assertEquals(.018f, EditorBlurPresets.size(1), 0);
        assertEquals(.045f, EditorBlurPresets.size(2), 0);
    }

    @Test public void restoredPresetIndexIsClamped() {
        assertEquals(.006f, EditorBlurPresets.size(-100), 0);
        assertEquals(.045f, EditorBlurPresets.size(100), 0);
    }

    @Test public void legacySizeChoosesNearestPresetWithoutMutatingIt() {
        float legacy = .023f;
        assertEquals(1, EditorBlurPresets.nearest(legacy));
        assertEquals(.023f, legacy, 0);
        for (int i = 0; i < 3; i++) assertEquals(i, EditorBlurPresets.nearest(EditorBlurPresets.size(i)));
    }

    @Test public void toolSizeRestorationRejectsNonFiniteAndOutOfRangeValues() {
        assertEquals(.025f, EditorBlurPresets.brush(Float.NaN), 0);
        assertEquals(.025f, EditorBlurPresets.brush(Float.POSITIVE_INFINITY), 0);
        assertEquals(.001f, EditorBlurPresets.brush(-1), 0);
        assertEquals(.1f, EditorBlurPresets.brush(1), 0);
        assertEquals(.034f, EditorBlurPresets.brush(.034f), 0);
    }
}
