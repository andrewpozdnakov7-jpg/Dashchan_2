package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Bundle;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SettingsSearchIndex {
	private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");

	private SettingsSearchIndex() {}

	private enum Screen {
		GENERAL(R.string.general) {
			@Override
			ContentFragment createFragment() { return new GeneralFragment(); }
		},
		FORUMS(R.string.forums) {
			@Override
			ContentFragment createFragment() { return new ChansFragment(); }
		},
		EXPERIMENTAL(R.string.experimental_features) {
			@Override
			ContentFragment createFragment() { return new ExperimentalFragment(); }
		},
		INTERFACE(R.string.user_interface) {
			@Override
			ContentFragment createFragment() { return new InterfaceFragment(); }
		},
		COMBINED_FEEDS(R.string.experimental_features, R.string.combined_feeds) {
			@Override
			ContentFragment createFragment() { return new CombinedFeedsFragment(); }
		},
		GESTURES(R.string.user_interface, R.string.gesture_controls) {
			@Override
			ContentFragment createFragment() { return new GestureSettingsFragment(); }
		},
		POST_MARKS(R.string.user_interface, R.string.post_marks_colors) {
			@Override
			ContentFragment createFragment() { return new PostMarksColorsFragment(); }
		},
		CONTENTS(R.string.contents) {
			@Override
			ContentFragment createFragment() { return new ContentsFragment(); }
		},
		MEDIA(R.string.media) {
			@Override
			ContentFragment createFragment() { return new MediaFragment(); }
		},
		AUTOHIDE(R.string.autohide) {
			@Override
			ContentFragment createFragment() { return new AutohideFragment(); }
		},
		ABOUT(R.string.about) {
			@Override
			ContentFragment createFragment() { return new AboutFragment(); }
		},
		SHORTCUT(R.string.custom_application_shortcut) {
			@Override
			ContentFragment createFragment() { return new CustomShortcutFragment(); }
		},
		ACCESSIBILITY(R.string.accessibility) {
			@Override
			ContentFragment createFragment() { return new AccessibilityFragment(); }
		};

		private final int[] breadcrumbResIds;

		Screen(int... breadcrumbResIds) {
			this.breadcrumbResIds = breadcrumbResIds;
		}

		abstract ContentFragment createFragment();

		String getBreadcrumb(Context context) {
			StringBuilder builder = new StringBuilder();
			for (int resId : breadcrumbResIds) {
				if (builder.length() > 0) {
					builder.append(" \u203a ");
				}
				builder.append(context.getString(resId));
			}
			return builder.toString();
		}
	}

	public static final class Entry {
		private final Screen screen;
		private final String chanName;
		private final String preferenceKey;
		private final String title;
		private final String breadcrumb;
		private final String searchableText;

		private Entry(Context context, Screen screen, int titleResId, int summaryResId, String preferenceKey) {
			this(screen, context.getString(titleResId), summaryResId != 0 ? context.getString(summaryResId) : null,
					screen.getBreadcrumb(context), null, preferenceKey);
		}

		private Entry(Screen screen, CharSequence title, CharSequence summary, String breadcrumb,
				String chanName, String preferenceKey) {
			this.screen = screen;
			this.chanName = chanName;
			this.preferenceKey = preferenceKey;
			this.title = title.toString();
			this.breadcrumb = breadcrumb;
			searchableText = normalize(title + " " + breadcrumb + " " + (summary != null ? summary : ""));
		}

		public String getTitle() {
			return title;
		}

		public String getBreadcrumb() {
			return breadcrumb;
		}

		private boolean matches(String[] words) {
			for (String word : words) {
				if (!searchableText.contains(word)) {
					return false;
				}
			}
			return true;
		}

		public ContentFragment createFragment() {
			ContentFragment fragment = chanName != null ? new ChanFragment(chanName) : screen.createFragment();
			if (fragment instanceof PreferenceFragment) {
				Bundle arguments = fragment.getArguments();
				if (arguments == null) {
					arguments = new Bundle();
					fragment.setArguments(arguments);
				}
				PreferenceFragment.putSearchTarget(arguments, preferenceKey, title);
			}
			return fragment;
		}
	}

	private static void add(Context context, List<Entry> entries, Screen screen, int titleResId,
			int summaryResId, String preferenceKey) {
		entries.add(new Entry(context, screen, titleResId, summaryResId, preferenceKey));
	}

	private static void add(Context context, List<Entry> entries, Screen screen, int titleResId) {
		add(context, entries, screen, titleResId, 0, null);
	}

	private static void add(List<Entry> entries, Screen screen, CharSequence title, CharSequence summary,
			String breadcrumb, String chanName, String preferenceKey) {
		entries.add(new Entry(screen, title, summary, breadcrumb, chanName, preferenceKey));
	}

	private static void addChan(Context context, List<Entry> entries, String breadcrumb, String chanName,
			int titleResId, int summaryResId, String preferenceKey) {
		add(entries, Screen.FORUMS, context.getText(titleResId),
				summaryResId != 0 ? context.getText(summaryResId) : null, breadcrumb, chanName, preferenceKey);
	}

	private static void addChanEntries(Context context, List<Entry> entries) {
		ChanManager manager = ChanManager.getInstance();
		for (Chan chan : manager.getAvailableChans()) {
			CharSequence chanTitle = "dvach".equals(chan.name) ? context.getText(R.string.forum_dvach)
					: "fourchan".equals(chan.name) ? context.getText(R.string.forum_fourchan)
					: chan.configuration.getTitle();
			if (StringUtils.isEmpty(chanTitle)) {
				chanTitle = chan.name;
			}
			String forumsBreadcrumb = Screen.FORUMS.getBreadcrumb(context);
			String breadcrumb = forumsBreadcrumb + " \u203a " + chanTitle;
			add(entries, Screen.FORUMS, chanTitle, null, forumsBreadcrumb, chan.name, null);
			ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(null);
			ChanConfiguration.Deleting deleting = board.allowDeleting
					? chan.configuration.safe().obtainDeleting(null) : null;

			if (!chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
				addChan(context, entries, breadcrumb, chan.name, R.string.default_starting_board, 0,
						Preferences.KEY_DEFAULT_BOARD_NAME.bind(chan.name));
			}
			if (board.allowCatalog) {
				addChan(context, entries, breadcrumb, chan.name, R.string.load_catalog, R.string.load_catalog__summary,
						Preferences.KEY_LOAD_CATALOG.bind(chan.name));
			}
			if (chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING)) {
				addChan(context, entries, breadcrumb, chan.name, R.string.hide_ai_posts, 0,
						Preferences.KEY_HIDE_AI_POSTS.bind(chan.name));
				addChan(context, entries, breadcrumb, chan.name, R.string.classic_monkey_responses,
						R.string.classic_monkey_responses__summary,
						Preferences.KEY_CLASSIC_MONKEY_RESPONSES.bind(chan.name));
			}
			if (deleting != null && deleting.password) {
				addChan(context, entries, breadcrumb, chan.name, R.string.password_for_removal,
						R.string.password_for_removal__summary, Preferences.KEY_PASSWORD.bind(chan.name));
			}
			if (chan.configuration.getSupportedCaptchaTypes() != null
					&& chan.configuration.getSupportedCaptchaTypes().size() > 1) {
				addChan(context, entries, breadcrumb, chan.name, R.string.captcha_type, 0,
						Preferences.KEY_CAPTCHA.bind(chan.name));
			}
			if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_CAPTCHA_PASS)) {
				ChanConfiguration.Authorization authorization = chan.configuration.safe().obtainCaptchaPass();
				if (authorization != null && authorization.fieldsCount > 0) {
					addChan(context, entries, breadcrumb, chan.name, R.string.captcha_pass, R.string.captcha_pass__summary,
							Preferences.KEY_CAPTCHA_PASS.bind(chan.name));
				}
			}
			if ("dvach".equals(chan.name)) {
				addChan(context, entries, breadcrumb, chan.name, R.string.auto_bump, R.string.auto_bump__summary,
						Preferences.KEY_AUTO_BUMP_ENABLED);
				addChan(context, entries, breadcrumb, chan.name, R.string.manage_auto_bump,
						R.string.manage_auto_bump__summary, null);
			}
			if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_USER_AUTHORIZATION)) {
				ChanConfiguration.Authorization authorization = chan.configuration.safe().obtainUserAuthorization();
				if (authorization != null && authorization.fieldsCount > 0) {
					addChan(context, entries, breadcrumb, chan.name, R.string.user_authorization, 0,
							Preferences.KEY_USER_AUTHORIZATION.bind(chan.name));
				}
			}
			Map<String, Boolean> customPreferences = chan.configuration.getCustomPreferences();
			if (customPreferences != null) {
				for (String key : customPreferences.keySet()) {
					ChanConfiguration.CustomPreference preference =
							chan.configuration.safe().obtainCustomPreference(key);
					if (preference != null && preference.title != null) {
						add(entries, Screen.FORUMS, preference.title, preference.summary, breadcrumb, chan.name, key);
					}
				}
			}
			addChan(context, entries, breadcrumb, chan.name, R.string.manage_cookies, 0, null);

			ArrayList<String> domains = chan.locator.getChanHosts(true);
			boolean localMode = chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE) || domains.isEmpty();
			if (!localMode) {
				addChan(context, entries, breadcrumb, chan.name, R.string.domain_name, 0,
						Preferences.KEY_DOMAIN.bind(chan.name));
				addChan(context, entries, breadcrumb, chan.name, R.string.proxy, 0,
						Preferences.KEY_PROXY.bind(chan.name));
			}
			if (chan.locator.isHttpsConfigurable()) {
				addChan(context, entries, breadcrumb, chan.name, R.string.secure_connection,
						R.string.secure_connection__summary, Preferences.KEY_USE_HTTPS.bind(chan.name));
			}
			if (chan.configuration.getOption(ChanConfiguration.OPTION_READ_THREAD_PARTIALLY)) {
				addChan(context, entries, breadcrumb, chan.name, R.string.partial_thread_loading,
						R.string.partial_thread_loading__summary,
						Preferences.KEY_PARTIAL_THREAD_LOADING.bind(chan.name));
			}
			if (!manager.isBuiltInChan(chan.name)) {
				addChan(context, entries, breadcrumb, chan.name, R.string.uninstall_extension, 0, null);
			}
		}
	}

	public static List<Entry> create(Context context) {
		ArrayList<Entry> entries = new ArrayList<>();

		add(context, entries, Screen.GENERAL, R.string.general);
		add(context, entries, Screen.FORUMS, R.string.forums);
		add(context, entries, Screen.EXPERIMENTAL, R.string.experimental_features);
		add(context, entries, Screen.INTERFACE, R.string.user_interface);
		add(context, entries, Screen.CONTENTS, R.string.contents);
		add(context, entries, Screen.MEDIA, R.string.media);
		add(context, entries, Screen.AUTOHIDE, R.string.autohide);
		add(context, entries, Screen.ABOUT, R.string.about);
		add(context, entries, Screen.SHORTCUT, R.string.custom_application_shortcut);
		add(context, entries, Screen.ACCESSIBILITY, R.string.accessibility);

		add(context, entries, Screen.GENERAL, R.string.language, 0, Preferences.KEY_LOCALE);
		add(context, entries, Screen.GENERAL, R.string.close_pages, R.string.close_pages__summary,
				Preferences.KEY_CLOSE_ON_BACK);
		add(context, entries, Screen.GENERAL, R.string.restore_pages, R.string.restore_pages__summary,
				Preferences.KEY_RESTORE_PAGES);
		add(context, entries, Screen.GENERAL, R.string.remember_history, 0, Preferences.KEY_REMEMBER_HISTORY);
		add(context, entries, Screen.GENERAL, R.string.merge_pages, R.string.merge_pages__summary,
				Preferences.KEY_MERGE_CHANS);
		add(context, entries, Screen.GENERAL, R.string.internal_browser, R.string.internal_browser__sumamry,
				Preferences.KEY_INTERNAL_BROWSER);
		add(context, entries, Screen.GENERAL, R.string.use_javascript_for_recaptcha,
				R.string.use_javascript_for_recaptcha__summary, Preferences.KEY_RECAPTCHA_JAVASCRIPT);
		add(context, entries, Screen.GENERAL, R.string.captcha_solving, R.string.captcha_solving__summary,
				Preferences.KEY_CAPTCHA_SOLVING);
		add(context, entries, Screen.GENERAL, R.string.secure_connection, R.string.secure_connection__summary,
				Preferences.KEY_USE_HTTPS_GENERAL);
		add(context, entries, Screen.GENERAL, R.string.verify_certificate, R.string.verify_certificate__summary,
				Preferences.KEY_VERIFY_CERTIFICATE);

		add(context, entries, Screen.EXPERIMENTAL, R.string.whats_new_preview,
				R.string.whats_new_preview__summary, null);
		add(context, entries, Screen.EXPERIMENTAL, R.string.hardware_video_acceleration,
				R.string.hardware_video_acceleration__summary, Preferences.KEY_HARDWARE_VIDEO_ACCELERATION);
		add(context, entries, Screen.EXPERIMENTAL, R.string.image_editor,
				R.string.image_editor__summary, Preferences.KEY_IMAGE_EDITOR);
		add(context, entries, Screen.EXPERIMENTAL, R.string.open_configured_attachment_folder,
				R.string.open_configured_attachment_folder__summary,
				Preferences.KEY_OPEN_CONFIGURED_ATTACHMENT_FOLDER);
		if (BuildConfig.ENABLE_LOCAL_TRANSLATION) {
			add(context, entries, Screen.EXPERIMENTAL, R.string.local_translation,
					R.string.local_translation__summary, Preferences.KEY_LOCAL_TRANSLATION);
			add(context, entries, Screen.EXPERIMENTAL, R.string.translation_native_language, 0,
					Preferences.KEY_TRANSLATION_NATIVE_LANGUAGE);
			if (BuildConfig.ENABLE_GOOGLE_TRANSLATION || BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION) {
				add(context, entries, Screen.EXPERIMENTAL, R.string.translation_engine, 0,
						Preferences.KEY_TRANSLATION_ENGINE);
			}
			add(context, entries, Screen.EXPERIMENTAL, R.string.translation_automatic,
					R.string.translation_automatic__summary, Preferences.KEY_TRANSLATION_AUTO);
			add(context, entries, Screen.EXPERIMENTAL, R.string.translation_language_package);
		}
		add(context, entries, Screen.EXPERIMENTAL, R.string.video_audio_boost,
				R.string.video_audio_boost__summary, Preferences.KEY_VIDEO_AUDIO_BOOST);
		add(context, entries, Screen.EXPERIMENTAL, R.string.video_audio_boost_level, 0,
				Preferences.KEY_VIDEO_AUDIO_BOOST_DB);
		if (BuildConfig.ALLOW_GMS_SECURITY_PROVIDER) {
			add(context, entries, Screen.EXPERIMENTAL, R.string.use_gms_security_provider,
					R.string.use_gms_security_provider__summary, Preferences.KEY_USE_GMS_PROVIDER);
		}
		add(context, entries, Screen.EXPERIMENTAL, R.string.video_diagnostics_start,
				R.string.video_diagnostics_start__summary, null);
		add(context, entries, Screen.EXPERIMENTAL, R.string.combined_feeds,
				R.string.combined_feeds__summary, Preferences.KEY_COMBINED_FEEDS_ENABLED);
		add(context, entries, Screen.COMBINED_FEEDS, R.string.configure_combined_feeds,
				R.string.configure_combined_feeds__summary, null);

		add(context, entries, Screen.INTERFACE, R.string.application_name, 0, Preferences.KEY_APPLICATION_NAME);
		add(context, entries, Screen.INTERFACE, R.string.application_logo);
		add(context, entries, Screen.INTERFACE, R.string.thumbnail_scale, 0, Preferences.KEY_THUMBNAILS_SCALE);
		add(context, entries, Screen.INTERFACE, R.string.crop_thumbnails, R.string.crop_thumbnails__summary,
				Preferences.KEY_CUT_THUMBNAILS);
		add(context, entries, Screen.INTERFACE, R.string.active_scrollbar, 0, Preferences.KEY_ACTIVE_SCROLLBAR);
		add(context, entries, Screen.INTERFACE, R.string.scroll_thread_when_scrolling_gallery, 0,
				Preferences.KEY_SCROLL_THREAD_GALLERY);
		add(context, entries, Screen.INTERFACE, R.string.themes);
		add(context, entries, Screen.INTERFACE, R.string.automatic_day_night_themes,
				R.string.automatic_day_night_themes__summary, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME);
		add(context, entries, Screen.INTERFACE, R.string.day_theme, 0, Preferences.KEY_DAY_THEME);
		add(context, entries, Screen.INTERFACE, R.string.night_theme, 0, Preferences.KEY_NIGHT_THEME);
		add(context, entries, Screen.INTERFACE, R.string.enable_predictive_back,
				R.string.enable_predictive_back__summary, Preferences.KEY_PREDICTIVE_BACK);
		add(context, entries, Screen.GESTURES, R.string.gesture_controls);
		add(context, entries, Screen.INTERFACE, R.string.headers_order, 0, Preferences.KEY_PAGES_LIST);
		add(context, entries, Screen.INTERFACE, R.string.initial_position, 0, Preferences.KEY_DRAWER_INITIAL_POSITION);
		add(context, entries, Screen.INTERFACE, R.string.paged_board_navigation,
				R.string.paged_board_navigation__summary, Preferences.KEY_PAGE_BY_PAGE);
		add(context, entries, Screen.INTERFACE, R.string.display_hidden_threads, 0,
				Preferences.KEY_DISPLAY_HIDDEN_THREADS);
		add(context, entries, Screen.INTERFACE, R.string.hide_threads_with_swipe, 0,
				Preferences.KEY_HIDE_THREADS_WITH_SWIPE);
		add(context, entries, Screen.INTERFACE, R.string.remove_hidden_posts, R.string.remove_hidden_posts__summary,
				Preferences.KEY_REMOVE_HIDDEN_POSTS);
		add(context, entries, Screen.INTERFACE, R.string.display_post_year, 0, Preferences.KEY_DISPLAY_POST_YEAR);
		add(context, entries, Screen.INTERFACE, R.string.hide_thread_title, R.string.hide_thread_title__summary,
				Preferences.KEY_HIDE_THREAD_TITLE);
		add(context, entries, Screen.INTERFACE, R.string.max_lines_count, R.string.max_lines_count__summary,
				Preferences.KEY_POST_MAX_LINES);
		add(context, entries, Screen.INTERFACE, R.string.all_attachments, R.string.all_attachments__summary,
				Preferences.KEY_ALL_ATTACHMENTS);
		add(context, entries, Screen.INTERFACE, R.string.highlight_unread_posts, 0,
				Preferences.KEY_HIGHLIGHT_UNREAD);
		add(context, entries, Screen.INTERFACE, R.string.highlight_my_posts, 0, Preferences.KEY_SHOW_MY_POSTS);
		add(context, entries, Screen.POST_MARKS, R.string.post_marks_colors);
		add(context, entries, Screen.INTERFACE, R.string.advanced_search, R.string.advanced_search__summary,
				Preferences.KEY_ADVANCED_SEARCH);
		add(context, entries, Screen.INTERFACE, R.string.display_post_icons, R.string.display_post_icons__summary,
				Preferences.KEY_DISPLAY_ICONS);
		add(context, entries, Screen.INTERFACE, R.string.hide_personal_data_block, 0,
				Preferences.KEY_HIDE_PERSONAL_DATA);
		add(context, entries, Screen.INTERFACE, R.string.huge_captcha, 0, Preferences.KEY_HUGE_CAPTCHA);

		add(context, entries, Screen.GESTURES, R.string.video_volume_gesture,
				R.string.video_volume_gesture__summary, Preferences.KEY_VIDEO_VOLUME_GESTURE);
		add(context, entries, Screen.GESTURES, R.string.video_volume_gesture_target, 0,
				Preferences.KEY_VIDEO_VOLUME_GESTURE_TARGET);
		add(context, entries, Screen.GESTURES, R.string.video_volume_gesture_sensitivity,
				R.string.video_volume_gesture_sensitivity__search_summary,
				Preferences.KEY_VIDEO_VOLUME_GESTURE_SENSITIVITY);
		add(context, entries, Screen.GESTURES, R.string.video_volume_gesture_area);
		add(context, entries, Screen.GESTURES, R.string.video_double_tap_seek,
				R.string.video_double_tap_seek__summary, Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK);
		add(context, entries, Screen.GESTURES, R.string.video_double_tap_seek_interval, 0,
				Preferences.KEY_VIDEO_DOUBLE_TAP_SEEK_INTERVAL);

		add(context, entries, Screen.POST_MARKS, R.string.post_marks_color_source, 0,
				Preferences.KEY_POST_MARKS_COLOR_MODE);
		add(context, entries, Screen.POST_MARKS, R.string.user_posts_color, 0,
				Preferences.KEY_USER_POST_MARK_COLOR);
		add(context, entries, Screen.POST_MARKS, R.string.replies_to_me_color, 0,
				Preferences.KEY_REPLY_POST_MARK_COLOR);
		add(context, entries, Screen.POST_MARKS, R.string.reset_post_marks_colors,
				R.string.reset_post_marks_colors__summary, null);

		add(context, entries, Screen.CONTENTS, R.string.refresh_open_thread, 0,
				Preferences.KEY_AUTO_REFRESH_INTERVAL);
		add(context, entries, Screen.CONTENTS, R.string.cyclical_threads_refresh_mode, 0,
				Preferences.KEY_CYCLICAL_REFRESH);
		add(context, entries, Screen.CONTENTS, R.string.favorite_threads_order, 0,
				Preferences.KEY_FAVORITES_ORDER);
		add(context, entries, Screen.CONTENTS, R.string.add_thread_on_reply, 0,
				Preferences.KEY_FAVORITE_ON_REPLY);
		add(context, entries, Screen.CONTENTS, R.string.watch_initially, R.string.watch_initially__summary,
				Preferences.KEY_WATCHER_WATCH_INITIALLY);
		add(context, entries, Screen.CONTENTS, R.string.refresh_favorites, 0,
				Preferences.KEY_WATCHER_REFRESH_INTERVAL);
		add(context, entries, Screen.CONTENTS, R.string.background_reply_check,
				R.string.background_reply_check__summary, Preferences.KEY_BACKGROUND_REPLY_CHECK);
		add(context, entries, Screen.CONTENTS, R.string.wifi_only, 0, Preferences.KEY_WATCHER_WIFI_ONLY);
		add(context, entries, Screen.CONTENTS, R.string.reply_notifications, 0, "reply_notifications");
		add(context, entries, Screen.CONTENTS, R.string.clear_cache);

		add(context, entries, Screen.MEDIA, R.string.load_thumbnails, 0, Preferences.KEY_LOAD_THUMBNAILS);
		add(context, entries, Screen.MEDIA, R.string.load_nearest_image, 0, Preferences.KEY_LOAD_NEAREST_IMAGE);
		add(context, entries, Screen.MEDIA, R.string.detailed_file_name, R.string.detailed_file_name__summary,
				Preferences.KEY_DOWNLOAD_DETAIL_NAME);
		add(context, entries, Screen.MEDIA, R.string.original_file_name, R.string.original_file_name__summary,
				Preferences.KEY_DOWNLOAD_ORIGINAL_NAME);
		add(context, entries, Screen.MEDIA, R.string.download_directory);
		add(context, entries, Screen.MEDIA, R.string.if_file_already_exists, 0,
				Preferences.KEY_DOWNLOAD_CONFLICT_MODE);
		add(context, entries, Screen.MEDIA, R.string.show_download_configuration_dialog, 0,
				Preferences.KEY_DOWNLOAD_SUBDIR);
		add(context, entries, Screen.MEDIA, R.string.subdirectory_pattern, 0, Preferences.KEY_SUBDIR_PATTERN);
		add(context, entries, Screen.MEDIA, R.string.notify_when_download_is_completed,
				R.string.notify_when_download_is_completed__summary, Preferences.KEY_NOTIFY_DOWNLOAD_COMPLETE);
		add(context, entries, Screen.MEDIA, R.string.use_built_in_video_player,
				R.string.use_built_in_video_player__summary, Preferences.KEY_USE_VIDEO_PLAYER);
		add(context, entries, Screen.MEDIA, R.string.action_on_playback_completion, 0,
				Preferences.KEY_VIDEO_COMPLETION);
		add(context, entries, Screen.MEDIA, R.string.play_after_scroll, R.string.play_after_scroll__summary,
				Preferences.KEY_VIDEO_PLAY_AFTER_SCROLL);
		add(context, entries, Screen.MEDIA, R.string.seek_any_frame, R.string.seek_any_frame__summary,
				Preferences.KEY_VIDEO_SEEK_ANY_FRAME);
		add(context, entries, Screen.MEDIA, R.string.video_picture_in_picture,
				R.string.video_picture_in_picture__summary, Preferences.KEY_VIDEO_PICTURE_IN_PICTURE);
		add(context, entries, Screen.MEDIA, R.string.video_picture_in_picture_auto,
				R.string.video_picture_in_picture_auto__summary, Preferences.KEY_VIDEO_PICTURE_IN_PICTURE_AUTO);
		add(context, entries, Screen.MEDIA, R.string.video_screen_off_action,
				R.string.video_screen_off_action__summary, Preferences.KEY_VIDEO_SCREEN_OFF_ACTION);
		add(context, entries, Screen.MEDIA, R.string.enable_video_playback_speed_control,
				R.string.enable_video_playback_speed_control__summary, Preferences.KEY_VIDEO_PLAYBACK_SPEED_CONTROL);
		add(context, entries, Screen.MEDIA, R.string.remember_video_playback_speed,
				R.string.remember_video_playback_speed__summary, Preferences.KEY_REMEMBER_VIDEO_PLAYBACK_SPEED);
		add(context, entries, Screen.MEDIA, R.string.persist_video_playback_speed,
				R.string.persist_video_playback_speed__summary, Preferences.KEY_PERSIST_VIDEO_PLAYBACK_SPEED);
		add(context, entries, Screen.MEDIA, R.string.attachment_video_preview,
				R.string.attachment_video_preview__summary, Preferences.KEY_ATTACHMENT_VIDEO_PREVIEW);
		add(context, entries, Screen.MEDIA, R.string.cache_size, 0, Preferences.KEY_CACHE_SIZE);
		add(context, entries, Screen.MEDIA, R.string.clear_cache);

		add(context, entries, Screen.ACCESSIBILITY, R.string.application_font, 0,
				Preferences.KEY_APPLICATION_FONT);
		add(context, entries, Screen.ACCESSIBILITY, R.string.install_custom_font,
				R.string.install_custom_font__summary, null);
		add(context, entries, Screen.ACCESSIBILITY, R.string.delete_custom_font);
		add(context, entries, Screen.ACCESSIBILITY, R.string.text_scale, 0, Preferences.KEY_TEXT_SCALE);
		add(context, entries, Screen.ACCESSIBILITY, R.string.volume_buttons_text_scale,
				R.string.volume_buttons_text_scale__summary, Preferences.KEY_VOLUME_BUTTONS_TEXT_SCALE);
		add(context, entries, Screen.ACCESSIBILITY, R.string.rounded_dialogs, R.string.rounded_dialogs__summary,
				Preferences.KEY_ROUNDED_DIALOGS);
		add(context, entries, Screen.ACCESSIBILITY, R.string.rounded_dialogs_radius, 0,
				Preferences.KEY_ROUNDED_DIALOGS_RADIUS);

		add(context, entries, Screen.ABOUT, R.string.statistics);
		add(context, entries, Screen.ABOUT, R.string.backup_data, R.string.backup_data__summary, null);
		add(context, entries, Screen.ABOUT, R.string.changelog);
		if (BuildConfig.ALLOW_APPLICATION_SELF_UPDATE) {
			if (BuildConfig.ALLOW_BETA_UPDATE_CHANNEL) {
				add(context, entries, Screen.ABOUT, R.string.update_channel, 0, Preferences.KEY_UPDATE_CHANNEL);
			}
			add(context, entries, Screen.ABOUT, R.string.automatic_update_check, 0,
					Preferences.KEY_UPDATE_AUTO_CHECK_ENABLED);
			add(context, entries, Screen.ABOUT, R.string.check_for_updates);
		}
		add(context, entries, Screen.ABOUT, R.string.project_author);
		add(context, entries, Screen.ABOUT, R.string.based_on_dashchan, R.string.based_on_dashchan__summary, null);
		add(context, entries, Screen.ABOUT, R.string.contact_email);
		add(context, entries, Screen.ABOUT, R.string.foss_licenses, R.string.foss_licenses__summary, null);

		addChanEntries(context, entries);

		Collections.sort(entries, (first, second) ->
				normalize(first.title).compareTo(normalize(second.title)));
		return entries;
	}

	public static List<Entry> search(List<Entry> entries, String query) {
		String normalized = normalize(query).trim();
		if (StringUtils.isEmpty(normalized)) {
			return Collections.emptyList();
		}
		String[] words = normalized.split("\\s+");
		ArrayList<Entry> result = new ArrayList<>();
		for (Entry entry : entries) {
			if (entry.matches(words)) {
				result.add(entry);
			}
		}
		return result;
	}

	private static String normalize(String value) {
		String normalized = DIACRITICS_PATTERN.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
				.replaceAll("").toLowerCase(Locale.ROOT);
		return normalized.replace('\u0451', '\u0435');
	}
}
