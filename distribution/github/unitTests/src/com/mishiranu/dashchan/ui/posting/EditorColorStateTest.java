package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.ui.posting.photo.EditorColorState;
import java.util.Arrays;
import org.junit.Test;

public class EditorColorStateTest {
    @Test public void primaryColorsAndOpaqueContract() {
        assertEquals(0xffff0000, EditorColorState.hsv(0, 1, 1));
        assertEquals(0xff00ff00, EditorColorState.hsv(120, 1, 1));
        assertEquals(0xff0000ff, EditorColorState.hsv(240, 1, 1));
        assertEquals(0xffffffff, new EditorColorState(0x10ffffff).color());
    }
    @Test public void rgbIsUnchangedUntilAnActualColorAction() {
        for (int rgb : new int[] {0xff123456, 0xffffffff, 0xff000000, 0xffa8c7fa}) {
            EditorColorState state = new EditorColorState(rgb);
            assertEquals(rgb, state.color());
            state.selectedCell(); assertEquals(rgb, state.color());
            state.setValue(state.value()); assertEquals(rgb, state.color());
        }
        assertEquals(-1, new EditorColorState(0xff123456).selectedCell());
    }
    @Test public void grayAndBlackRetainTheMeaningfulHue() {
        EditorColorState state = new EditorColorState(0xff0000ff);
        state.setRgb(0xff808080); assertEquals(240, state.hue(), .00001);
        state.setRgb(0xff000000); assertEquals(240, state.hue(), .00001);
        state.setHueSaturation(state.hue(), 1); state.setValue(1);
        assertEquals(0xff0000ff, state.color());
    }
    @Test public void continuousHsvDoesNotRoundTripThroughRgb() {
        EditorColorState state = new EditorColorState(0xffffffff);
        state.setHueSaturation(219.12345, .345678); state.setValue(.456789);
        assertEquals(219.12345, state.hue(), .00000001);
        assertEquals(.345678, state.saturation(), .00000001);
        assertEquals(.456789, state.value(), .00000001);
        state.setHueSaturation(-30, 2); state.setValue(-1);
        assertEquals(330, state.hue(), .000001); assertEquals(1, state.saturation(), 0);
        assertEquals(0xff000000, state.color());
    }
    @Test public void gridHasNeutralTopRowAndDeterministicCells() {
        assertEquals(0xffffffff, EditorColorState.cell(0)); assertEquals(0xff000000, EditorColorState.cell(11));
        for (int i = 0; i < 120; i++) assertEquals(255, EditorColorState.cell(i) >>> 24);
        assertEquals(0xffff0000, EditorColorState.cell(5 * 12 + 5));
        assertEquals(0xff00ff00, EditorColorState.cell(5 * 12 + 11));
    }
    @Test public void hexValidationNeverAcceptsAlphaOrMalformedInput() {
        assertEquals(Integer.valueOf(0xff12abcd), EditorColorState.parseHex(" #12aBcD "));
        for (String text : new String[] {"12ABCD", "#123", "#FF123456", "#ZZZZZZ", ""}) assertNull(EditorColorState.parseHex(text));
        assertEquals("#12ABCD", EditorColorState.hex(0xff12abcd));
    }
    @Test public void confirmedRecentColorsAreOpaqueUniqueAndBounded() {
        assertEquals(Arrays.asList(0xff000001, 0xff000002), EditorColorState.recent(Arrays.asList(0xff000002, 0xff000001, 0xff000002), 0x00000001));
        assertEquals(8, EditorColorState.recent(Arrays.asList(1,2,3,4,5,6,7,8,9,10), 11).size());
    }
}
