package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DrawerMotionControllerTest {
	@Test public void slideAndSuspendRestoreContentBeforeWideModeReparentsIt() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(Build.VERSION.SDK_INT >= 34 && ValueAnimator.areAnimatorsEnabled());
			String key = Preferences.KEY_NEW_INTERFACE_MOTION;
			Object previous = Preferences.PREFERENCES.getAll().get(key);
			Object previousRounded = Preferences.PREFERENCES.getAll().get(Preferences.KEY_ROUNDED_DIALOGS);
			Object previousRadius = Preferences.PREFERENCES.getAll().get(Preferences.KEY_ROUNDED_DIALOGS_RADIUS);
			DrawerMotionController controller = null;
			try {
				Preferences.PREFERENCES.edit().put(key, true).close();
				Preferences.PREFERENCES.edit().put(Preferences.KEY_ROUNDED_DIALOGS, true)
						.put(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, 28).close();
				android.content.Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
				DrawerLayout layout = new DrawerLayout(context);
				FrameLayout panel = new FrameLayout(context);
				FrameLayout insetHost = new FrameLayout(context);
				View content = new View(context);
				layout.addView(new View(context), new DrawerLayout.LayoutParams(-1, -1));
				layout.addView(panel, new DrawerLayout.LayoutParams(80, -1, GravityCompat.START));
				panel.addView(insetHost); insetHost.addView(content);
				panel.layout(0, 0, 80, 100); insetHost.layout(0, 0, 80, 100); content.layout(0, 0, 80, 100);
				panel.setBackgroundColor(0xffffffff);
				Drawable originalBackground = panel.getBackground();
				insetHost.setForeground(new ColorDrawable(0xffaaaaaa));
				content.setAlpha(.7f); content.setScaleX(.9f); content.setScaleY(.85f);
				content.setTranslationX(5f); content.setPivotX(7f); content.setPivotY(8f);
				controller = new DrawerMotionController(layout, panel, content);
				controller.onDrawerSlide(panel, .5f);
				assertEquals(1f, insetHost.getAlpha(), 0f);
				assertEquals(1f, insetHost.getScaleX(), 0f); assertEquals(1f, insetHost.getScaleY(), 0f);
				assertEquals(0f, insetHost.getTranslationX(), 0f);
				assertNull(insetHost.getTag(R.id.drawer_motion_owner));
				assertEquals(PixelFormat.TRANSLUCENT, opacity(panel.getBackground()));
				assertTrue(panel.getClipToOutline());
				assertSame(controller, content.getTag(R.id.drawer_motion_owner));
				assertTrue(content.getAlpha() < .7f); assertTrue(content.getScaleX() < .9f);
				float partialAlpha = content.getAlpha();
				controller.onDrawerStateChanged(DrawerLayout.STATE_DRAGGING);
				assertSame(controller, content.getTag(R.id.drawer_motion_owner));
				assertEquals(partialAlpha, content.getAlpha(), 0f);
				controller.onDrawerSlide(panel, 1f);
				assertNull(content.getTag(R.id.drawer_motion_owner));
				assertEquals(.7f, content.getAlpha(), 0f);
				assertEquals(.9f, content.getScaleX(), 0f);
				controller.onDrawerSlide(panel, .5f);
				controller.suspend(); insetHost.removeView(content);
				assertNull(content.getTag(R.id.drawer_motion_owner));
				assertEquals(.7f, content.getAlpha(), 0f); assertEquals(.9f, content.getScaleX(), 0f);
				assertEquals(.85f, content.getScaleY(), 0f); assertEquals(5f, content.getTranslationX(), 0f);
				assertEquals(7f, content.getPivotX(), 0f); assertEquals(8f, content.getPivotY(), 0f);
				Preferences.PREFERENCES.edit().put(key, false).close();
				controller.updatePolicy();
				// Appearance remains settings-driven when experimental motion is disabled.
				assertEquals(PixelFormat.TRANSLUCENT, opacity(panel.getBackground()));
				assertTrue(panel.getClipToOutline());
				Preferences.PREFERENCES.edit().put(Preferences.KEY_ROUNDED_DIALOGS, false).close();
				controller.updatePolicy();
				assertSame(originalBackground, panel.getBackground());
				assertFalse(panel.getClipToOutline());
			} finally {
				if (controller != null) controller.suspend();
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(key, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(key).close();
				if (previousRounded instanceof Boolean) Preferences.PREFERENCES.edit()
						.put(Preferences.KEY_ROUNDED_DIALOGS, (Boolean) previousRounded).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_ROUNDED_DIALOGS).close();
				if (previousRadius instanceof Integer) Preferences.PREFERENCES.edit()
						.put(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, (Integer) previousRadius).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_ROUNDED_DIALOGS_RADIUS).close();
			}
		});
	}

	@SuppressWarnings("deprecation") // DrawerLayout's opacity-based clipping contract.
	private static int opacity(Drawable drawable) { return drawable.getOpacity(); }
}
