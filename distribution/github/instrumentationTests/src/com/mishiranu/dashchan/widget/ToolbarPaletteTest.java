package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.util.OpaqueColors;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared deterministic fixtures for the approved palette examples; NOT executed. */
@RunWith(AndroidJUnit4.class)
public class ToolbarPaletteTest {
	@Test public void approvedExamplesMatchExactlyAndMeetContrast() {
		int[][] examples = {
			{0, 0xffeeeeee, 0xff5181b8, 0xffeaf0f6, 0xff272f3a, 0xff5a6575, 0xff4a5d76},
			{0, 0xffeef2ff, 0xffaf0a0f, 0xfff5e2e2, 0xff351e21, 0xff68535c, 0xff623f4c},
			{1, 0xff2c2c2c, 0xff6688cc, 0xff32353c, 0xffe3e8f0, 0xffabb5c7, 0xffbbc6da},
			{1, 0xff181818, 0xffff6600, 0xff2f2016, 0xfff2e5dc, 0xffc2b0a8, 0xffd9bfb1},
			{1, 0xff000000, 0xffaaaaaa, 0xff000000, 0xffeaeced, 0xffb5bac2, 0xffc8ccd3},
			{0, 0xfff0f2ee, 0xff388e3c, 0xffe7f1e8, 0xff243128, 0xff566762, 0xff446057},
			{1, 0xffeeeeee, 0xffffffff, 0xfff0f0f0, 0xff6c6d6e, 0xff6a6d71, 0xff828488}
		};
		for (int[] example : examples) {
			ToolbarPalette palette = ToolbarPalette.resolve(example[0] != 0, example[1], example[2]);
			assertArrayEquals(new int[]{example[3], example[4], example[5], example[6]},
					new int[]{palette.background, palette.title, palette.subtitle, palette.icon});
			readable(palette);
		}
	}
	@Test public void extremeTransparentAndNearBlackInputsStayOpaqueReadableAndUncached() {
		for (boolean dark : new boolean[]{false, true}) for (int accent : new int[]{Color.BLACK, Color.WHITE, 0x805181b8, Color.TRANSPARENT}) {
			ToolbarPalette palette = ToolbarPalette.resolve(dark, dark ? 0xff202020 : 0xffeeeeee, accent);
			readable(palette);
		}
		ToolbarPalette black = ToolbarPalette.resolve(true, Color.BLACK, Color.WHITE);
		assertEquals(Color.BLACK, black.background);
		assertNotEquals(Color.BLACK, ToolbarPalette.resolve(true, 0xff010101, Color.WHITE).background);
		assertNotEquals(ToolbarPalette.resolve(false, 0xffeeeeee, 0xff5181b8).background,
				ToolbarPalette.resolve(false, 0xffeeeeee, 0xff388e3c).background);
		assertEquals(ToolbarPalette.resolve(false, 0xffeeeeee, OpaqueColors.over(0x805181b8, 0xffeeeeee)).background,
				ToolbarPalette.resolve(false, 0xffeeeeee, 0x805181b8).background);
	}
	private void readable(ToolbarPalette palette) {
		for (int role : new int[]{palette.background, palette.title, palette.subtitle, palette.icon}) assertEquals(255, Color.alpha(role));
		assertTrue(OpaqueColors.contrast(palette.title, palette.background) >= 4.5);
		assertTrue(OpaqueColors.contrast(palette.subtitle, palette.background) >= 4.5);
		assertTrue(OpaqueColors.contrast(palette.icon, palette.background) >= 3);
	}
}
