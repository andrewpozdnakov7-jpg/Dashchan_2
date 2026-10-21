package com.mishiranu.dashchan.widget;

/** Explicit opt-in shape/spacing roles. The outer drawer radius remains a user preference. */
public final class AppearanceShapes {
	public static final int SMALL = 8, MEDIUM = 12, LARGE = 16, EXTRA_LARGE = 28;
	public static final int FIELD_STROKE = 1, FIELD_FOCUS_STROKE = 2, ACTION_MIN_HEIGHT = 48;
	public static final int SPACE_4 = 4, SPACE_8 = 8, SPACE_12 = 12, SPACE_16 = 16, SPACE_24 = 24;
	// Explicit stages 3–5 profile; these do not change drawer/protected compatibility roles.
	public static final int SETTING_MIN_HEIGHT = 56, SETTING_SUMMARY_MIN_HEIGHT = 64;
	public static final int SERVICE_MIN_HEIGHT = 48, SERVICE_METADATA_MIN_HEIGHT = 56;
	public static final int STANDARD_FIELD_MIN_HEIGHT = 56, STANDARD_TOOLBAR_MIN_HEIGHT = 56;
	public static final int STANDARD_SEARCH_MIN_HEIGHT = 40, STANDARD_TARGET = 48, STANDARD_DIALOG_MAX_WIDTH = 560;
	private AppearanceShapes() {}
	public static float full(float width, float height) { return Math.min(width, height) / 2f; }
}
