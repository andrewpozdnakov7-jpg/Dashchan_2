package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Frozen pre-refactor 100-step policy, including its white-on-tie fallback. */
@RunWith(AndroidJUnit4.class)
public class PlayerButtonsColorCompatibilityTest {
	private int before(int color, int background, double minimum) {
		background = ColorUtils.setAlphaComponent(background, 255);
		color = ColorUtils.compositeColors(color, background);
		if (ColorUtils.calculateContrast(color, background) >= minimum) return color;
		int end = ColorUtils.calculateContrast(Color.BLACK, background) > ColorUtils.calculateContrast(Color.WHITE, background)
				? Color.BLACK : Color.WHITE;
		for (int i = 1; i <= 100; i++) {
			int candidate = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(color, end, i / 100f), 255);
			if (ColorUtils.calculateContrast(candidate, background) >= minimum) return candidate;
		}
		return end;
	}
	@Test public void commonMathPreservesPlayerEditorResultsExactly() {
		int[] colors = {Color.WHITE, Color.BLACK, Color.BLUE, Color.YELLOW, 0xff777777, 0xff123456, 0x80334455};
		for (int bg : colors) for (int text : colors) for (double ratio : new double[]{3, 4.5}) {
			assertEquals(before(text, bg, ratio), PlayerButtonsColors.contrast(text, bg, ratio));
		}
		for (float ratio : new float[]{0, .055f, .075f, .09f, .12f, .15f, .42f, 1}) {
			assertEquals(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(Color.WHITE, Color.BLUE, ratio), 255),
					PlayerButtonsColors.opaqueBlend(Color.WHITE, Color.BLUE, ratio));
		}
	}
}
