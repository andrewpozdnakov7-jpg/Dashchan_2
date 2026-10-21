package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.OpaqueColors;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pure palette inputs and detached Views: no preference writes, networking or Activity startup. */
@RunWith(AndroidJUnit4.class)
public class AppearanceTokensTest {
	@Test public void blendsKeepOpaqueAlphaAndPoliciesAlwaysReachRequestedContrast() {
		float[] ratios = {0, .075f, .09f, .10f, .13f, .16f, .18f, .26f, .38f, .7f, 1};
		for (float ratio : ratios) {
			int bg = OpaqueColors.blend(0xffeeeeee, 0xff123456, ratio);
			assertEquals(255, Color.alpha(bg));
			for (OpaqueColors.Policy policy : OpaqueColors.Policy.values()) {
				int text = OpaqueColors.readable(0x80777777, bg, 4.5, policy);
				assertEquals(255, Color.alpha(text)); assertTrue(ColorUtils.calculateContrast(text, bg) >= 4.5);
			}
		}
		assertEquals(ColorUtils.compositeColors(0x80112233, Color.WHITE), OpaqueColors.over(0x80112233, Color.WHITE));
	}
	@Test public void referenceDrawerColorsRemainExact() {
		AppearanceColors light = AppearanceColors.drawerCompact(false, true, Color.WHITE, Color.WHITE, Color.BLUE);
		AppearanceColors dark = AppearanceColors.drawerCompact(true, true, Color.BLACK, Color.BLACK, Color.BLUE);
		assertEquals(0xfff3f5fa, light.surface); assertEquals(0xff161c24, dark.surface);
		assertEquals(0xff1c2028, light.onSurface); assertEquals(0xffe4e9f2, dark.onSurface);
		assertEquals(0xff535e70, light.onSurfaceVariant); assertEquals(0xffb7c2d3, dark.onSurfaceVariant);
		assertEquals(0xffdce1eb, light.outlineVariant); assertEquals(0xff414a59, dark.outlineVariant);
		assertEquals(0xffe3e7ef, light.surfaceContainer); assertEquals(0xff2b3442, dark.surfaceContainer);
		assertEquals(0xff394454, light.disabledContent); assertEquals(0xffc4cedd, dark.disabledContent);
		assertEquals(0xffe3edfc, light.primaryContainer); assertEquals(0xff243f63, dark.primaryContainer);
		assertEquals(0xff285887, light.onPrimaryContainer); assertEquals(0xffc5dcff, dark.onPrimaryContainer);
		assertTrue(ColorUtils.calculateContrast(light.onPrimaryContainer, light.primaryContainer) >= 4.5);
		assertEquals(0xffc9deff, light.unreadContainer); assertEquals(0xff2c4d78, dark.unreadContainer);
		assertEquals(0xff123f7a, light.onUnreadContainer); assertEquals(0xffe0ecff, dark.onUnreadContainer);
		assertEquals(0xffffedea, light.errorContainer); assertEquals(0xff492f30, dark.errorContainer);
		assertEquals(0xff8c1d18, light.onErrorContainer); assertEquals(0xffffb4ab, dark.onErrorContainer);
	}
	@Test public void customDynamicAndAmoledUseRealOpaqueContainersAndTranslucentRipple() {
		for (boolean dark : new boolean[]{false, true}) for (int window : new int[]{Color.WHITE, Color.BLACK, 0x80334455}) {
			int accent = 0x6011aaee;
			AppearanceColors colors = AppearanceColors.drawerCompact(dark, false, window, 0x80667788, accent);
			assertPairs(colors);
			assertEquals(ColorUtils.compositeColors(accent, colors.surface), colors.primary);
			assertEquals(36, Color.alpha(colors.ripple));
			if (window == Color.BLACK) assertEquals(Color.BLACK, colors.surface);
			AppearanceColors compatibility = AppearanceColors.interfaceCompatibility(dark, window,
					0x80667788, accent, 0x99112233, 0xffbb2233);
			assertPairs(compatibility);
			assertTrue(OpaqueColors.contrast(compatibility.error, compatibility.surface) >= 4.5);
			assertTrue(OpaqueColors.contrast(compatibility.errorIcon, compatibility.surface) >= 3);
			assertEquals(36, Color.alpha(compatibility.ripple));
		}
	}
	private void assertPairs(AppearanceColors c) {
		for (int bg : new int[]{c.window, c.surface, c.surfaceContainer, c.primary, c.primaryContainer, c.unreadContainer, c.errorContainer}) {
			assertEquals(255, Color.alpha(bg));
		}
		assertTrue(ColorUtils.calculateContrast(c.onSurface, c.surface) >= 4.5);
		assertTrue(ColorUtils.calculateContrast(c.onSurfaceVariant, c.surface) >= 4.5);
		assertTrue(ColorUtils.calculateContrast(c.onPrimary, c.primary) >= 4.5);
		assertTrue(ColorUtils.calculateContrast(c.onPrimaryContainer, c.primaryContainer) >= 4.5);
		assertTrue(ColorUtils.calculateContrast(c.onUnreadContainer, c.unreadContainer) >= 4.5);
		assertTrue(ColorUtils.calculateContrast(c.onErrorContainer, c.errorContainer) >= 4.5);
	}
	@Test public void explicitTypographyIsIdempotentRestorableAndConfigurationAware() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
			TextView view = new TextView(base); view.setTypeface(Typeface.MONOSPACE); view.setTextSize(17);
			view.setLineSpacing(7, 1.2f); view.setLetterSpacing(.03f); view.setText("before");
			AppearanceTypography.Snapshot snapshot = new AppearanceTypography.Snapshot(view);
			Typeface original = view.getTypeface(); float size = view.getTextSize();
			AppearanceTypography.apply(view, AppearanceTypography.Role.DRAWER_COMPACT);
			float compact = view.getTextSize();
			AppearanceTypography.apply(view, AppearanceTypography.Role.DRAWER_COMPACT);
			assertEquals(compact, view.getTextSize(), .01f);
			assertEquals(0f, view.getLineSpacingExtra(), .01f); assertEquals(1f, view.getLineSpacingMultiplier(), .01f);
			view.setText("latest"); snapshot.restore(view);
			assertEquals(original, view.getTypeface()); assertEquals(size, view.getTextSize(), .01f);
			assertEquals(7f, view.getLineSpacingExtra(), .01f); assertEquals(1.2f, view.getLineSpacingMultiplier(), .01f);
			assertEquals(.03f, view.getLetterSpacing(), .001f); assertEquals("latest", view.getText().toString());
			Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = 2;
			Configuration normal = new Configuration(config); normal.fontScale = 1;
			assertNotEquals(new AppearanceTypography.Configuration(base.createConfigurationContext(normal)),
					new AppearanceTypography.Configuration(base.createConfigurationContext(config)));
			assertNotEquals(AppearanceColors.drawerCompact(false, false, Color.WHITE, Color.WHITE, Color.RED),
					AppearanceColors.drawerCompact(false, false, Color.WHITE, Color.WHITE, Color.BLUE));
		});
	}
}
