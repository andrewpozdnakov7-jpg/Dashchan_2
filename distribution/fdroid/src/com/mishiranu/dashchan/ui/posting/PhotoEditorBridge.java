package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/** F-Droid always uses the existing editor, including after restoring a GitHub preferences backup. */
public final class PhotoEditorBridge {
	private PhotoEditorBridge() {}

	public static boolean isAvailable() {
		return false;
	}

	@StringRes public static int getTitleResId() {
		return 0;
	}

	@StringRes public static int getSummaryResId() {
		return 0;
	}

	@Nullable public static Intent createIntent(Context context, String sourceHash, String sourceName, int attachmentIndex) {
		return null;
	}
}
