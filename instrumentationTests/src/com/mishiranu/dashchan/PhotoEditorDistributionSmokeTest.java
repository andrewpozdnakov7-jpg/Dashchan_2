package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.ImageEditorActivity;
import com.mishiranu.dashchan.ui.posting.PhotoEditorBridge;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run on a disposable test installation; temporarily changes/restores one experimental preference. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorDistributionSmokeTest {
	@Test public void defaultAndExplicitDisableWorkInBothDistributions() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_PHOTO_EDITOR);
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			try {
				Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_PHOTO_EDITOR).close();
				assertTrue(PhotoEditorBridge.isAvailable());
				assertTrue(Preferences.isNewPhotoEditorEnabled());
				Intent defaultIntent = ImageEditorActivity.createIntent(context, "fixture-hash", "fixture.png", 3);
				assertNotNull(defaultIntent.getComponent());
				String expected = "com.mishiranu.dashchan.ui.posting.ExperimentalImageEditorActivity";
				assertEquals(expected, defaultIntent.getComponent().getClassName());
				assertEquals("fixture-hash", defaultIntent.getStringExtra("sourceHash"));
				assertEquals("fixture.png", defaultIntent.getStringExtra("sourceName"));
				assertEquals(3, defaultIntent.getIntExtra("attachmentIndex", -1));
				assertNotNull(context.getPackageManager().resolveActivity(defaultIntent, 0));
				Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, false).close();
				assertFalse(Preferences.isNewPhotoEditorEnabled());
				Intent legacyIntent = ImageEditorActivity.createIntent(context, "fixture-hash", "fixture.png", 3);
				assertNotNull(legacyIntent.getComponent());
				assertEquals(ImageEditorActivity.class.getName(), legacyIntent.getComponent().getClassName());
			} finally {
				if (previous instanceof Boolean) {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, (Boolean) previous).close();
				} else {
					Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_PHOTO_EDITOR).close();
				}
			}
		});
	}

	@Test public void restoredOptInRoutesToTheSharedRegisteredEditor() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_PHOTO_EDITOR);
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			try {
				Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, true).close();
				assertTrue(Preferences.isNewPhotoEditorEnabled());
				Intent intent = ImageEditorActivity.createIntent(context, "fixture-hash", "fixture.png", 3);
				assertNotNull(intent.getComponent());
				String expected = "com.mishiranu.dashchan.ui.posting.ExperimentalImageEditorActivity";
				assertEquals(expected, intent.getComponent().getClassName());
				assertEquals("fixture-hash", intent.getStringExtra("sourceHash"));
				assertEquals("fixture.png", intent.getStringExtra("sourceName"));
				assertEquals(3, intent.getIntExtra("attachmentIndex", -1));
				assertNotNull(context.getPackageManager().resolveActivity(intent, 0));
			} finally {
				if (previous instanceof Boolean) {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, (Boolean) previous).close();
				} else {
					Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_PHOTO_EDITOR).close();
				}
			}
		});
	}
}
