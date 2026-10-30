package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AttachmentGridMotionControllerTest {
	@Test public void finishingCloseCancelsOnceAndRestoresTheWindowDim() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Dialog windowOwner = new Dialog(ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()));
			FrameLayout content = new FrameLayout(windowOwner.getContext()); content.layout(0, 0, 200, 200);
			content.setAlpha(.7f); content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			windowOwner.getWindow().setDimAmount(.6f);
			AttachmentGridMotionController motion = new AttachmentGridMotionController(content, windowOwner.getWindow(), null);
			int[] cancelled = {0};
			try {
				motion.install(motion.new Scene(true, () -> cancelled[0]++));
				assertTrue(motion.isRunning()); motion.finish(); motion.finish();
				content.getViewTreeObserver().dispatchOnPreDraw();
				assertEquals(1, cancelled[0]); assertFalse(motion.isRunning());
				assertNull(content.getTag(R.id.gallery_motion_owner)); assertEquals(.7f, content.getAlpha(), 0f);
				assertEquals(.6f, windowOwner.getWindow().getAttributes().dimAmount, 0f);
				assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, content.getImportantForAccessibility());
			} finally { motion.dispose(); windowOwner.dismiss(); }
		});
	}
	@Test public void forcedDismissDoesNotDispatchThePendingUserCancel() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Dialog windowOwner = new Dialog(ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()));
			View content = new View(windowOwner.getContext());
			AttachmentGridMotionController motion = new AttachmentGridMotionController(content, windowOwner.getWindow(), null);
			int[] cancelled = {0};
			try {
				motion.install(motion.new Scene(true, () -> cancelled[0]++)); motion.dispose(); motion.finish();
				content.getViewTreeObserver().dispatchOnPreDraw();
				assertEquals(0, cancelled[0]); assertNull(content.getTag(R.id.gallery_motion_owner));
				assertFalse(motion.isRunning());
			} finally { motion.dispose(); windowOwner.dismiss(); }
		});
	}
}
