package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Shared surface policy; does not enable experimental motion or modify saved user choices. */
@RunWith(AndroidJUnit4.class)
public class DrawerAppearanceTest {
	@Test public void roundingUsesTheExistingSwitchAndRadiusIncludingZeroAndRtl() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previousRounded = Preferences.PREFERENCES.getAll().get(Preferences.KEY_ROUNDED_DIALOGS);
			Object previousRadius = Preferences.PREFERENCES.getAll().get(Preferences.KEY_ROUNDED_DIALOGS_RADIUS);
			try {
				FrameLayout panel = new FrameLayout(InstrumentationRegistry.getInstrumentation().getTargetContext());
				panel.layout(0, 0, 400, 800); panel.setBackgroundColor(0xffffffff);
				Drawable background = panel.getBackground();
				DrawerAppearance appearance = new DrawerAppearance(panel);
				Preferences.PREFERENCES.edit().put(Preferences.KEY_ROUNDED_DIALOGS, false).close();
				appearance.update(); assertSame(background, panel.getBackground()); assertFalse(panel.getClipToOutline());
				for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
					panel.setLayoutDirection(direction);
					for (int radius : new int[] {12, 40}) {
						Preferences.PREFERENCES.edit().put(Preferences.KEY_ROUNDED_DIALOGS, true)
								.put(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, radius).close();
						appearance.update(); assertTrue(panel.getClipToOutline());
						Outline outline = new Outline(); panel.getOutlineProvider().getOutline(panel, outline);
						assertEquals(radius * ResourceUtils.obtainDensity(panel), outline.getRadius(), .001f);
					}
				}
				Preferences.PREFERENCES.edit().put(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, 0).close();
				appearance.update(); assertSame(background, panel.getBackground()); assertFalse(panel.getClipToOutline());
			} finally {
				if (previousRounded instanceof Boolean) Preferences.PREFERENCES.edit()
						.put(Preferences.KEY_ROUNDED_DIALOGS, (Boolean) previousRounded).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_ROUNDED_DIALOGS).close();
				if (previousRadius instanceof Integer) Preferences.PREFERENCES.edit()
						.put(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, (Integer) previousRadius).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_ROUNDED_DIALOGS_RADIUS).close();
			}
		});
	}
}
