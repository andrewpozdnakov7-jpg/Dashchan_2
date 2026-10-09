package com.mishiranu.dashchan.ui.gallery;

/** Keeps the current gallery's input mode separate from the saved preference. */
final class TikTokPagingSession {
	private boolean hasPreference;
	private boolean preferenceEnabled;
	private int preferenceFilter;
	private boolean enabled;

	TikTokPagingSession() {}

	TikTokPagingSession(Snapshot snapshot) {
		if (snapshot != null) {
			hasPreference = true;
			preferenceEnabled = snapshot.preferenceEnabled;
			preferenceFilter = snapshot.preferenceFilter;
			enabled = snapshot.enabled && preferenceEnabled;
		}
	}

	void syncPreferences(boolean preferenceEnabled, int preferenceFilter) {
		if (!hasPreference || this.preferenceEnabled != preferenceEnabled
				|| this.preferenceFilter != preferenceFilter) {
			hasPreference = true;
			this.preferenceEnabled = preferenceEnabled;
			this.preferenceFilter = preferenceFilter;
			enabled = preferenceEnabled;
		}
	}

	void selectMode(boolean enabled, int filter) {
		hasPreference = true;
		preferenceEnabled = enabled;
		preferenceFilter = filter;
		this.enabled = enabled;
	}

	boolean selectItem(boolean matchesFilter) {
		// Opening an excluded attachment enters ordinary browsing. Matching later
		// attachments must not silently re-enable vertical/filtered navigation.
		if (!matchesFilter) enabled = false;
		return enabled;
	}

	boolean isEnabled() { return enabled; }

	Snapshot snapshot() {
		return hasPreference ? new Snapshot(preferenceEnabled, preferenceFilter, enabled) : null;
	}

	static final class Snapshot {
		final boolean preferenceEnabled;
		final int preferenceFilter;
		final boolean enabled;
		Snapshot(boolean preferenceEnabled, int preferenceFilter, boolean enabled) {
			this.preferenceEnabled = preferenceEnabled;
			this.preferenceFilter = preferenceFilter;
			this.enabled = enabled;
		}
	}
}
