package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class ImageAttachmentsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addHeader(R.string.images);
		addList(Preferences.KEY_LOAD_THUMBNAILS, enumList(Preferences.NetworkMode.values(), v -> v.value),
				Preferences.DEFAULT_LOAD_THUMBNAILS.value, R.string.load_thumbnails,
				enumResList(Preferences.NetworkMode.values(), v -> v.titleResId));

		addHeader(R.string.new_attachment_defaults);
		addCheck(true, Preferences.KEY_OPEN_CONFIGURED_ATTACHMENT_FOLDER,
				Preferences.DEFAULT_OPEN_CONFIGURED_ATTACHMENT_FOLDER,
				R.string.open_configured_attachment_folder,
				R.string.open_configured_attachment_folder__summary);
		addCheck(true, Preferences.KEY_IMAGE_EDITOR, Preferences.DEFAULT_IMAGE_EDITOR,
				R.string.image_editor, R.string.image_editor__summary);
		addCheck(true, Preferences.KEY_DEFAULT_ATTACHMENT_UNIQUE_HASH,
				Preferences.DEFAULT_ATTACHMENT_UNIQUE_HASH, R.string.unique_hash, 0);
		addCheck(true, Preferences.KEY_DEFAULT_ATTACHMENT_REENCODING,
				Preferences.DEFAULT_ATTACHMENT_REENCODING, R.string.reencode_image, 0);
		addCheck(true, Preferences.KEY_DEFAULT_ATTACHMENT_REMOVE_FILE_NAME,
				Preferences.DEFAULT_ATTACHMENT_REMOVE_FILE_NAME, R.string.remove_file_name, 0);
		addCheck(true, Preferences.KEY_DEFAULT_ATTACHMENT_REMOVE_METADATA,
				Preferences.DEFAULT_ATTACHMENT_REMOVE_METADATA, R.string.remove_metadata, 0);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.media_images_attachments), null);
	}
}
