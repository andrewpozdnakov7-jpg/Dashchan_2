package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;

/** Shared editor routing for both distributions; preserves the user's legacy-editor choice. */
public final class PhotoEditorBridge {
	private PhotoEditorBridge() {}

	public static boolean isAvailable() {
		return true;
	}

	@StringRes public static int getTitleResId() {
		return R.string.new_photo_editor;
	}

	@StringRes public static int getSummaryResId() {
		return R.string.new_photo_editor__summary;
	}

	@Nullable public static Intent createIntent(Context context, String sourceHash, String sourceName, int attachmentIndex) {
		return Preferences.isNewPhotoEditorEnabled()
				? ExperimentalImageEditorActivity.createIntent(context, sourceHash, sourceName, attachmentIndex) : null;
	}
}
