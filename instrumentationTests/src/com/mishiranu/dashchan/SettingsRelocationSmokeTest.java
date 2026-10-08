package com.mishiranu.dashchan;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.fragment.app.FragmentFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.preference.DrawerOrderFragment;
import com.mishiranu.dashchan.ui.preference.InterfaceFragment;
import com.mishiranu.dashchan.ui.preference.MediaFragment;
import com.mishiranu.dashchan.ui.preference.ContentsFragment;
import com.mishiranu.dashchan.ui.preference.OfflineTranslationFragment;
import com.mishiranu.dashchan.ui.preference.PipDiagnosticsFragment;
import com.mishiranu.dashchan.ui.preference.ReplyNotificationsFragment;
import com.mishiranu.dashchan.ui.preference.SettingsSearchIndex;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Search destinations and recreation only: no preference writes, requests, or downloads. */
@RunWith(AndroidJUnit4.class)
public class SettingsRelocationSmokeTest {
	@Test public void backgroundTapClosingIsOptInAndSearchOpensMediaSettings() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			assertFalse(Preferences.DEFAULT_CLOSE_GALLERY_ON_BACKGROUND_TAP);
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			assertRoute(context, SettingsSearchIndex.create(context), R.string.close_gallery_on_background_tap,
					MediaFragment.class, R.string.media);
		});
	}

	@Test public void movedSettingsOpenTheirNewScreens() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			assertRoute(context, entries, R.string.drawer_section_order, InterfaceFragment.class,
					R.string.user_interface);
			assertRoute(context, entries, R.string.drawer_custom_order, DrawerOrderFragment.class,
					R.string.user_interface);
			assertRoute(context, entries, R.string.collapse_long_open_threads, InterfaceFragment.class,
					R.string.user_interface);
			assertRoute(context, entries, R.string.thread_gallery_button, InterfaceFragment.class,
					R.string.user_interface);
			assertRoute(context, entries, R.string.show_original_post_title, InterfaceFragment.class,
					R.string.user_interface);
			assertRoute(context, entries, R.string.hardware_video_acceleration, MediaFragment.class,
					R.string.media);
			assertRoute(context, entries, R.string.thread_page_preload, ContentsFragment.class,
					R.string.contents);
			for (int title : new int[]{R.string.replies_and_notifications, R.string.track_replies,
					R.string.tracked_replies_local_check, R.string.watch_initially,
					R.string.refresh_favorites, R.string.background_reply_check,
					R.string.wifi_only, R.string.reply_notifications}) {
				assertRoute(context, entries, title, ReplyNotificationsFragment.class, R.string.contents);
			}
			assertRoute(context, entries, R.string.pip_diagnostic_mode, PipDiagnosticsFragment.class,
					R.string.experimental_features);
		});
	}

	@Test public void translationSearchRespectsTheDistributionAndOpensTheNewScreen() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			for (int title : new int[]{R.string.local_translation, R.string.translation_native_language,
					R.string.translation_engine, R.string.translation_automatic,
					R.string.translation_language_package, R.string.persistent_translation_cache,
					R.string.clear_translation_cache}) {
				if (BuildConfig.ENABLE_LOCAL_TRANSLATION) {
					assertRoute(context, entries, title, OfflineTranslationFragment.class, R.string.contents);
				} else {
					for (SettingsSearchIndex.Entry entry : entries) {
						assertNotEquals(context.getString(title), entry.getTitle());
					}
				}
			}
		});
	}

	@Test public void newScreensCanBeRestoredWithoutConstructorArguments() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FragmentFactory factory = new FragmentFactory();
			for (Class<?> type : new Class<?>[]{OfflineTranslationFragment.class, PipDiagnosticsFragment.class}) {
				assertEquals(type, factory.instantiate(type.getClassLoader(), type.getName()).getClass());
			}
		});
	}

	private static void assertRoute(Context context, List<SettingsSearchIndex.Entry> entries, int titleResId,
			Class<? extends ContentFragment> type, int parentResId) {
		int matches = 0;
		for (SettingsSearchIndex.Entry entry : entries) {
			if (context.getString(titleResId).equals(entry.getTitle())) {
				matches++;
				assertEquals(type, entry.createFragment().getClass());
				String parent = context.getString(parentResId);
				assertTrue(entry.getBreadcrumb().equals(parent)
						|| entry.getBreadcrumb().startsWith(parent + " \u203a "));
			}
		}
		assertEquals("Missing or duplicated setting: " + context.getString(titleResId), 1, matches);
	}
}
