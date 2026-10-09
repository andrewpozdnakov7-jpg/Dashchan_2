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
		StartupNotices.NoticeDialog restored = new StartupNotices.NoticeDialog();
		restored.onCreate(saved);
		assertFalse(restored.isCancelable());
	}
}
