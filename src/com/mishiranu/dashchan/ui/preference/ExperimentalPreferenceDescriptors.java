package com.mishiranu.dashchan.ui.preference;

import androidx.annotation.StringRes;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.PhotoEditorBridge;

/** Shared descriptions for media and experimental toggles; screens and search use the same availability. */
final class ExperimentalPreferenceDescriptors {
	static final class Toggle {
		final String key;
		final boolean defaultValue;
		final boolean available;
		@StringRes final int titleResId;
		@StringRes final int summaryResId;

		private Toggle(String key, boolean defaultValue, boolean available,
				int titleResId, int summaryResId) {
			this.key = key;
			this.defaultValue = defaultValue;
			this.available = available;
			this.titleResId = titleResId;
			this.summaryResId = summaryResId;
		}
	}

	private ExperimentalPreferenceDescriptors() {}

	static Toggle interfaceMotion() {
		return new Toggle(Preferences.KEY_NEW_INTERFACE_MOTION, Preferences.DEFAULT_NEW_INTERFACE_MOTION,
				BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION,
				R.string.new_interface_motion, R.string.new_interface_motion_summary);
	}

	static Toggle photoEditor() {
		return new Toggle(Preferences.KEY_NEW_PHOTO_EDITOR, Preferences.DEFAULT_NEW_PHOTO_EDITOR,
				PhotoEditorBridge.isAvailable(), PhotoEditorBridge.getTitleResId(), PhotoEditorBridge.getSummaryResId());
	}


	static Toggle compactDialogs() {
		return new Toggle(Preferences.KEY_COMPACT_DIALOGS, Preferences.DEFAULT_COMPACT_DIALOGS,
				true, R.string.compact_dialogs, R.string.compact_dialogs_summary);
	}

	static Toggle videoThumbnails() {
		return new Toggle(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, Preferences.DEFAULT_UPDATE_VIDEO_THUMBNAILS,
				true, R.string.update_video_thumbnails, R.string.update_video_thumbnails_summary);
	}
}
