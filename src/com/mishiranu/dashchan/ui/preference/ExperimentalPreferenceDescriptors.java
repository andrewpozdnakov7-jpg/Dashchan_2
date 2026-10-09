package com.mishiranu.dashchan.ui.preference;

import androidx.annotation.StringRes;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.PhotoEditorBridge;

/** Shared descriptions for four experimental toggles; callers keep their existing screen/search order. */
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

	static Toggle discussionContext() {
		return new Toggle(Preferences.KEY_DISCUSSION_CONTEXT, Preferences.DEFAULT_DISCUSSION_CONTEXT,
				true, R.string.discussion_context, R.string.discussion_context_summary);
	}

	static Toggle outboxJournal() {
		return new Toggle(Preferences.KEY_OUTBOX_JOURNAL, Preferences.DEFAULT_OUTBOX_JOURNAL,
				true, R.string.outbox_title, R.string.outbox_experimental_summary);
	}
}
