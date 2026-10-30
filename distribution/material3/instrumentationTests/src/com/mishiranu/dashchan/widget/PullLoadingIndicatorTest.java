package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.loadingindicator.LoadingIndicatorDrawable;
import com.mishiranu.dashchan.util.ResourceUtils;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Material theme/drawable smoke fixture; never changes preferences or animation scale. */
@RunWith(AndroidJUnit4.class)
public class PullLoadingIndicatorTest {
	@Test public void realContainedDrawableWorksInsideTheExistingPlatformTheme() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Resources.Theme original = context.getTheme();
			LoadingIndicator indicator = PullLoadingIndicator.createMaterial(context);
			assertSame(original, context.getTheme()); assertNotSame(context, indicator.getContext());
			int size = Math.round(48f * ResourceUtils.obtainDensity(context));
			assertEquals(size, indicator.getContainerWidth()); assertEquals(size, indicator.getContainerHeight());
			indicator.setContainerColor(Color.WHITE); indicator.setIndicatorColor(Color.BLACK);
			LoadingIndicatorDrawable drawable = indicator.getDrawable(); drawable.setBounds(0, 0, size, size);
			Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
			try {
				drawable.setVisible(true, false, false); drawable.draw(new Canvas(image));
				boolean painted = false;
				for (int y = 0; y < size && !painted; y++) for (int x = 0; x < size; x++) {
					if (Color.alpha(image.getPixel(x, y)) > 0) { painted = true; break; }
				}
				assertTrue("The real Material drawable must paint without attaching its configuration view", painted);
			} finally { drawable.setVisible(false, false, false); drawable.setCallback(null); image.recycle(); }
		});
	}
}
