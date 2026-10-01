package com.mishiranu.dashchan.util;

/** Fixed allowlist for diagnostic grouping; never return a caller-supplied key or any value. */
final class AuditPreferenceKeys {
	private AuditPreferenceKeys() {}
	static String label(String key) {
		if (key == null) return "other";
		switch (key) {
			case "last_visited_forum": return "last_visited_forum";
			case "catalog_sort": return "catalog_sort";
			case "lock_drawer": return "lock_drawer";
			case "favorite_threads_collapsed": return "favorite_threads_collapsed";
			case "drawer_section_order": return "drawer_section_order";
			case "drawer_custom_order": return "drawer_custom_order";
			case "show_spoilers": return "show_spoilers";
			case "popup_color_mode": case "popup_background": case "popup_foreground": return "popup_colors";
			case "text_scale": case "subject_text_scale": case "metadata_text_scale": return "text_scale";
			case "thread_quick_navigation": case "thread_quick_navigation_transparency": return "quick_navigation";
			default: return "other";
		}
	}
}
