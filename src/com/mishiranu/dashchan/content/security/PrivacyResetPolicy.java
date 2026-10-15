package com.mishiranu.dashchan.content.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Allowlist, not a blacklist: future account/history settings must not survive by accident. */
public final class PrivacyResetPolicy {
	private static final Set<String> PRESENTATION = new HashSet<>(Arrays.asList((
			"locale theme automatic_day_night_theme day_theme night_theme application_font application_name application_logo " +
			"text_scale subject_text_scale metadata_text_scale volume_buttons_text_scale thumbnails_scale threads_view " +
			"touch_feedback touch_feedback_intensity active_scrollbar thread_quick_navigation thread_quick_navigation_transparency " +
			"popup_color_mode popup_background popup_foreground post_marks_color_mode user_post_mark_color reply_post_mark_color " +
			"rounded_dialogs rounded_dialogs_radius display_icons display_post_year hide_thread_title show_original_post_title " +
			"expanded_screen lock_drawer merge_chans pages_list close_on_back restore_pages predictive_back " +
			"drawer_custom_order drawer_section_order drawer_initial_position page_by_page remove_hidden_threads remove_hidden_posts " +
			"hide_threads_with_swipe all_attachments advanced_search " +
			"posting_editor_height posting_editor_lines posting_hide_name posting_hide_email posting_hide_password posting_hide_subject " +
			"hide_personal_data post_max_lines highlight_unread_posts huge_captcha cut_thumbnails " +
			"video_right_hand_controls video_completion video_play_after_scroll video_seek_any_frame video_start_muted " +
			"hardware_video_acceleration remember_last_video_position remember_video_playback_speed persist_video_playback_speed " +
			"video_playback_speed_control video_playback_speed_presets video_custom_playback_speed video_custom_playback_speed_value " +
			"video_volume_gesture video_volume_gesture_target video_volume_gesture_sensitivity video_local_volume_level " +
			"video_volume_gesture_portrait_width video_volume_gesture_portrait_top video_volume_gesture_portrait_bottom " +
			"video_volume_gesture_landscape_width video_volume_gesture_landscape_top video_volume_gesture_landscape_bottom " +
			"video_double_tap_seek video_double_tap_seek_interval video_picture_in_picture video_picture_in_picture_auto " +
			"video_audio_boost video_audio_boost_db video_zoom_gestures video_screen_off_action youtube_open_mode " +
			"scroll_thread_gallery scroll_gallery_to_current_file close_gallery_on_background_tap close_gallery_grid_on_background_tap " +
			"showcase_gallery sfw_mode show_my_posts show_spoilers attachment_video_preview " +
			"toolbar_title_size toolbar_title_customization toolbar_compact_title_size toolbar_adaptive_title toolbar_title_hyphenation " +
			"toolbar_min_title_size toolbar_title_size_step thread_gallery_button collapse_long_open_threads " +
			"experimental_new_photo_editor experimental_new_interface_motion experimental_new_posting_form experimental_new_video_player " +
			"experimental_thread_toolbar_action experimental_pip_diagnostic_mode swipe_reply " +
			"wallpaper_enabled wallpaper_id wallpaper_title wallpaper_dim_amount wallpaper_card_opacity " +
			"translation_native_language translation_engine local_translation experimental_translation_cache " +
			"cache_size load_thumbnails load_nearest_image image_editor default_attachment_unique_hash default_attachment_reencoding " +
			"default_attachment_remove_file_name default_attachment_remove_metadata " +
			"download_detail_name download_original_name download_conflict_mode notify_download_complete " +
			"check_updates_on_start update_auto_check_enabled update_channel trusted_extensions use_gms_provider " +
			"material3_announcement_read_count").split(" ")));
	private PrivacyResetPolicy() {}
	public static boolean keepPreference(String key) { return PRESENTATION.contains(key); }
	public static boolean isSafeLocalTreeId(String id) {
		if (id == null) return false;
		int colon = id.indexOf(':');
		if (colon <= 0 || colon == id.length() - 1) return false;
		for (String part : id.substring(colon + 1).split("/", -1)) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.indexOf('\\') >= 0) return false;
		}
		return true;
	}
	public static String approvedDownloadTarget(String approved, String current) {
		// Availability is not consent. Only an explicit settings change may revoke approval;
		// Preferences.setDownloadUriTree clears it when a different directory is selected.
		// Never substitute a new/current URI for the exact tree the user approved.
		return approved;
	}
	public static boolean keepStorageFile(String name) {
		return "themes.json".equals(name) || "themes.backup.json".equals(name) || "themes.restore.json".equals(name);
	}
}
