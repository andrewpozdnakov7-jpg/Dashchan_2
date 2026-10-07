package com.mishiranu.dashchan.ui.gallery;

/** Parameter policy only: never owns the player's lifecycle or the system PiP window. */
public enum PipDiagnosticMode {
	NORMAL("normal", -1), NO_HINT("no_hint", 0), ENTRY_HINT("entry_hint", 1), LAYOUT_HINT("layout_hint", 2),
	NO_SEAMLESS_RESIZE("no_seamless_resize", 3);

	public final String preferenceValue;
	public final int probeId;

	PipDiagnosticMode(String preferenceValue, int probeId) {
		this.preferenceValue = preferenceValue;
		this.probeId = probeId;
	}

	public static PipDiagnosticMode fromPreference(String value) {
		for (PipDiagnosticMode mode : values()) {
			if (mode.preferenceValue.equals(value)) return mode;
		}
		return NORMAL;
	}

	public boolean isDiagnostic() { return this != NORMAL; }
	public boolean allowsHintAtEntry() { return this != NO_HINT && this != NO_SEAMLESS_RESIZE; }
	public boolean isSeamlessResizeEnabled() { return this != NO_SEAMLESS_RESIZE; }
	public boolean allowsParameterUpdate(boolean inPictureInPicture) {
		return this == NORMAL || this == LAYOUT_HINT && inPictureInPicture;
	}
}
