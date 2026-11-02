package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.ComponentCallbacks2;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public class PreviewMemoryPolicyTest {
	@Test public void hidingUiKeepsPreviewsWhileMemoryRequestsStillClearThem() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int[] clears = {0};
			try (PreviewMemoryPolicy policy = new PreviewMemoryPolicy(context, () -> clears[0]++)) {
				policy.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
				policy.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
				assertEquals(0, clears[0]);
				policy.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
				assertEquals(1, clears[0]);
				policy.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
				assertEquals(2, clears[0]);
				policy.onLowMemory();
				assertEquals(3, clears[0]);
			}
		});
	}
}
