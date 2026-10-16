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
import com.mishiranu.dashchan.ui.preference.GallerySettingsFragment;
import com.mishiranu.dashchan.ui.preference.ImageAttachmentsFragment;
import com.mishiranu.dashchan.ui.preference.DownloadsFragment;
import com.mishiranu.dashchan.ui.preference.VideoPlayerSettingsFragment;
import com.mishiranu.dashchan.ui.preference.VideoPreloadFragment;
import com.mishiranu.dashchan.ui.preference.PlaybackSpeedFragment;
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
	@Test public void securitySettingsAreExposedOnlyByGithubDistribution() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			assertEquals("github".equals(BuildConfig.FLAVOR), BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
			for (int title : new int[]{R.string.security_title, R.string.security_primary_pin,
					R.string.security_additional_pin, R.string.security_authentication_mode,
					R.string.security_reauthentication,
					R.string.security_allow_screenshots, R.string.security_delete_downloads, R.string.security_guide}) {
				if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
					assertRoute(context, entries, title,
							com.mishiranu.dashchan.ui.preference.SecurityFragment.class, R.string.security_title);
				} else {
					for (SettingsSearchIndex.Entry entry : entries) assertNotEquals(context.getString(title), entry.getTitle());
				}
			}
			if (!BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
				assertTrue(com.mishiranu.dashchan.ui.preference.CategoriesFragment.class.isInstance(
						new com.mishiranu.dashchan.ui.preference.SecurityFragment()));
			}
		});
	}
	@Test public void backgroundTapClosingIsOptInAndSearchOpensGallerySettings() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			assertFalse(Preferences.DEFAULT_CLOSE_GALLERY_ON_BACKGROUND_TAP);
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			assertRoute(context, SettingsSearchIndex.create(context), R.string.close_gallery_on_background_tap,
					GallerySettingsFragment.class, R.string.media);
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
			assertRoute(context, entries, R.string.hardware_video_acceleration, VideoPlayerSettingsFragment.class,
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
			for (Class<?> type : new Class<?>[]{OfflineTranslationFragment.class, PipDiagnosticsFragment.class,
					GallerySettingsFragment.class, ImageAttachmentsFragment.class, DownloadsFragment.class,
					VideoPlayerSettingsFragment.class, VideoPreloadFragment.class, PlaybackSpeedFragment.class}) {
				assertEquals(type, factory.instantiate(type.getClassLoader(), type.getName()).getClass());
			}
		});
	}

	@Test public void mediaSearchRoutesEveryMovedSettingToItsDedicatedScreen() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			List<SettingsSearchIndex.Entry> entries = SettingsSearchIndex.create(context);
			for (int title : new int[]{R.string.gallery, R.string.close_gallery_on_background_tap,
					R.string.close_gallery_grid_on_background_tap, R.string.scroll_gallery_to_current_file}) {
				assertRoute(context, entries, title, GallerySettingsFragment.class, R.string.media);
			}
			for (int title : new int[]{R.string.media_images_attachments, R.string.load_thumbnails,
					R.string.open_configured_attachment_folder, R.string.image_editor, R.string.unique_hash,
					R.string.reencode_image, R.string.remove_file_name, R.string.remove_metadata}) {
				assertRoute(context, entries, title, ImageAttachmentsFragment.class, R.string.media);
			}
			for (int title : new int[]{R.string.downloads, R.string.detailed_file_name, R.string.original_file_name,
					R.string.download_directory, R.string.if_file_already_exists,
					R.string.show_download_configuration_dialog, R.string.subdirectory_pattern,
					R.string.notify_when_download_is_completed}) {
				assertRoute(context, entries, title, DownloadsFragment.class, R.string.media);
			}
			for (int title : new int[]{R.string.video_player, R.string.hardware_video_acceleration,
					R.string.use_built_in_video_player, R.string.remember_last_video_position, R.string.start_videos_muted,
					R.string.video_audio_boost, R.string.video_audio_boost_level, R.string.action_on_playback_completion,
					R.string.play_after_scroll, R.string.seek_any_frame, R.string.video_zoom_gestures,
					R.string.youtube_open_mode, R.string.video_picture_in_picture,
					R.string.video_picture_in_picture_auto, R.string.video_screen_off_action,
					R.string.playback_speed, R.string.attachment_video_preview}) {
				assertRoute(context, entries, title, VideoPlayerSettingsFragment.class, R.string.media);
			}
			assertRoute(context, entries, R.string.cache_size, MediaFragment.class, R.string.media);
			// File cache and post/database cache intentionally share a title, not a destination.
			List<SettingsSearchIndex.Entry> mediaCache = new java.util.ArrayList<>();
			List<SettingsSearchIndex.Entry> contentsCache = new java.util.ArrayList<>();
			for (SettingsSearchIndex.Entry entry : entries) if (context.getString(R.string.clear_cache).equals(entry.getTitle())) {
				if (entry.getBreadcrumb().equals(context.getString(R.string.media))) mediaCache.add(entry);
				else contentsCache.add(entry);
			}
			assertRoute(context, mediaCache, R.string.clear_cache, MediaFragment.class, R.string.media);
			assertRoute(context, contentsCache, R.string.clear_cache, ContentsFragment.class, R.string.contents);
			assertRoute(context, entries, R.string.media_preload, VideoPreloadFragment.class, R.string.media);
			assertRoute(context, entries, R.string.enable_video_playback_speed_control,
					PlaybackSpeedFragment.class, R.string.media);
			for (SettingsSearchIndex.Entry entry : entries) {
				if (context.getString(R.string.enable_video_playback_speed_control).equals(entry.getTitle())) {
					assertEquals(PlaybackSpeedFragment.class, entry.createFragment().getClass());
					assertEquals(context.getString(R.string.media) + " \u203a "
							+ context.getString(R.string.video_player) + " \u203a "
							+ context.getString(R.string.playback_speed), entry.getBreadcrumb());
				}
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
