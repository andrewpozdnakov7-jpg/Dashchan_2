package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

public class StartupNoticeTest {
	@Test public void counterRoundTripsToTheSamePrivateStoreWithoutVersionKeys() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		SharedPreferences original = StartupNotices.store(context);
		String key = "test-counter-roundtrip";
		try {
			assertTrue(original.edit().putInt(key, 2).commit());
			assertEquals(2, StartupNotices.store(context).getInt(key, 0));
		} finally { assertTrue(original.edit().remove(key).commit()); }
	}
	@Test public void localMaterialDialogThemeDoesNotReplaceTheApplicationTheme() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			Resources.Theme original = context.getTheme();
			MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
					new ContextThemeWrapper(context, R.style.Theme_Slooop_StartupNotice_Light));
			assertSame(original, context.getTheme());
			assertNotSame(original, builder.getContext().getTheme());
		});
	}
	@Test public void dialogRestorationDoesNotWriteAnAcknowledgement() {
		StartupNotices.NoticeDialog dialog = new StartupNotices.NoticeDialog();
		Bundle saved = new Bundle();
		dialog.onCreate(null);
		dialog.onSaveInstanceState(saved);
		assertEquals(0, saved.getInt("targetCount"));
		assertEquals(StartupNotices.KEY, saved.getString("noticeKey"));
		StartupNotices.NoticeDialog restored = new StartupNotices.NoticeDialog();
		restored.onCreate(saved);
		assertFalse(restored.isCancelable());
	}
	@Test public void explicitDesignChoicesPersistWithoutReusingTheOldAnnouncementCount() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			String designName = "fixture-startup-design-choice", noticeName = "fixture-startup-design-counter";
			context.deleteSharedPreferences(designName); context.deleteSharedPreferences(noticeName);
			try {
				com.mishiranu.dashchan.util.SharedPreferences design = new com.mishiranu.dashchan.util.SharedPreferences(context, designName);
				SharedPreferences notices = context.getSharedPreferences(noticeName, Context.MODE_PRIVATE);
				assertTrue(Preferences.DEFAULT_NEW_INTERFACE_MOTION);
				assertTrue(design.getBoolean(Preferences.KEY_NEW_INTERFACE_MOTION, Preferences.DEFAULT_NEW_INTERFACE_MOTION));
				assertTrue(notices.edit().putInt("material3-acknowledgements", 3).commit());
				assertTrue(new StartupNoticePolicy().claim(notices.getInt(StartupNotices.KEY, 0)));
				design.edit().put(Preferences.KEY_NEW_POSTING_FORM, false).put(Preferences.KEY_NEW_VIDEO_PLAYER, false).close();
				assertTrue(StartupNotices.NoticeDialog.persistChoice(design, notices, false, 1));
				assertFalse(design.getBoolean(Preferences.KEY_NEW_INTERFACE_MOTION, true));
				assertFalse(design.getBoolean(Preferences.KEY_NEW_POSTING_FORM, true));
				assertEquals(1, notices.getInt(StartupNotices.KEY, 0));
				// A fresh wrapper simulates restart: an explicit opt-out defeats the new default.
				com.mishiranu.dashchan.util.SharedPreferences restarted = new com.mishiranu.dashchan.util.SharedPreferences(context, designName);
				assertFalse(restarted.getBoolean(Preferences.KEY_NEW_INTERFACE_MOTION, Preferences.DEFAULT_NEW_INTERFACE_MOTION));
				assertTrue(StartupNotices.NoticeDialog.persistChoice(restarted, notices, true, 2));
				assertTrue(restarted.getBoolean(Preferences.KEY_NEW_INTERFACE_MOTION, false));
				assertTrue(restarted.getBoolean(Preferences.KEY_NEW_POSTING_FORM, false));
				assertTrue(restarted.getBoolean(Preferences.KEY_NEW_VIDEO_PLAYER, false));
				assertTrue(StartupNotices.NoticeDialog.persistChoice(restarted, notices, false, 3));
				assertTrue(restarted.getBoolean(Preferences.KEY_NEW_POSTING_FORM, false)); // Subchoices retained on opt-out.
				assertFalse(new StartupNoticePolicy().claim(notices.getInt(StartupNotices.KEY, 0)));
				assertEquals(3, notices.getInt("material3-acknowledgements", 0));
			} finally { context.deleteSharedPreferences(designName); context.deleteSharedPreferences(noticeName); }
		});
	}
}
