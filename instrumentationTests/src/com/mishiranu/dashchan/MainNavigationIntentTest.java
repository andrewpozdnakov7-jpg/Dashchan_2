package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.ui.MainNavigationIntent;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainNavigationIntentTest {
	private final Context context = ApplicationProvider.getApplicationContext();

	@Test public void internalEntryIsNotExportedAndTargetsTheSameHost() throws Exception {
		Intent intent = MainNavigationIntent.create(context).setAction(C.ACTION_BROWSER);
		ActivityInfo info = context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
		assertFalse(info.exported);
		assertEquals(MainActivity.class.getName(), info.targetActivity);
		assertEquals(C.ACTION_BROWSER, MainNavigationIntent.sanitize(context, intent).getAction());
	}

	@Test public void publicLinksDiscardAllExtrasAndGrants() {
		Intent intent = new Intent(context, MainActivity.class).setAction(Intent.ACTION_VIEW)
				.setData(Uri.parse("https://2ch.su/mobi/"))
				.putExtra("com.mishiranu.dashchan.extra.UPDATE_DATA_MAP", "forged")
				.putExtra(C.EXTRA_FROM_CLIENT, true)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
		intent.setClipData(ClipData.newPlainText("untrusted", "text"));
		Intent clean = MainNavigationIntent.sanitize(context, intent);
		assertNotNull(clean);
		assertEquals(intent.getData(), clean.getData());
		assertNull(clean.getExtras());
		assertNull(clean.getClipData());
		assertEquals(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, clean.getFlags());
	}

	@Test public void publicInternalCommandsAndNonWebUrisAreRejected() {
		for (String action : new String[] {C.ACTION_BROWSER, C.ACTION_PLAYER, C.ACTION_POSTING,
				C.ACTION_POSTING_SHARE, C.ACTION_LOCAL_ARCHIVE, C.ACTION_GALLERY,
				C.ACTION_RETURN_FROM_PICTURE_IN_PICTURE}) {
			assertNull(MainNavigationIntent.sanitize(context,
					new Intent(context, MainActivity.class).setAction(action)));
		}
		assertNull(MainNavigationIntent.sanitize(context, new Intent(context, MainActivity.class)
				.setAction(Intent.ACTION_VIEW).setData(Uri.parse("content://untrusted/file"))));
	}

	@Test public void launcherAndLegacyHandleUriRemainPublicWithoutInternalExtras() {
		Intent launcher = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
				.addCategory(Intent.CATEGORY_LAUNCHER).putExtra(C.EXTRA_BOARD_NAME, "forged");
		Intent clean = MainNavigationIntent.sanitize(context, launcher);
		assertTrue(clean.hasCategory(Intent.CATEGORY_LAUNCHER));
		assertNull(clean.getExtras());
		assertNotNull(MainNavigationIntent.sanitize(context, new Intent(context, MainActivity.class)
				.setAction("chan.intent.action.HANDLE_URI").setData(Uri.parse("http://2ch.su/mobi/"))));
	}

	/** Real MainActivity startup/recreation; run only on a disposable test install. */
	@Test public void frameworkPreservesInternalAliasAcrossStartupAndRecreation() {
		Intent intent = MainNavigationIntent.create(context).setAction(Intent.ACTION_MAIN)
				.putExtra("navigation_test_probe", "retained");
		try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
			scenario.onActivity(activity -> assertEquals("retained",
					activity.getIntent().getStringExtra("navigation_test_probe")));
			scenario.recreate();
			scenario.onActivity(activity -> assertEquals("retained",
					activity.getIntent().getStringExtra("navigation_test_probe")));
		}
	}
}
