package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.DownloadDirectoryPolicy;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.EditPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;

public class DownloadFoldersFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	public static int getTitleResId(DownloadDirectoryPolicy.Type type) {
		switch (type) {
			case IMAGES: return R.string.download_folder_images;
			case VIDEOS: return R.string.download_folder_videos;
			case AUDIO: return R.string.download_folder_audio;
			case BACKUPS: return R.string.download_folder_backups;
			default: return R.string.download_folder_other;
		}
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		for (DownloadDirectoryPolicy.Type type : DownloadDirectoryPolicy.Type.values()) {
			EditPreference preference = addEdit(type.preferenceKey, type.defaultName, getTitleResId(type),
					p -> Preferences.getDownloadFolderName(type), type.defaultName,
					InputType.TYPE_CLASS_TEXT);
			preference.setHelperText(getString(R.string.download_folder_name_hint));
			preference.setOnBeforeChangeListener((p, value) -> {
				String name = DownloadDirectoryPolicy.normalizeName(value);
				if (name == null) {
					ClickableToast.show(R.string.download_folder_name_invalid);
					return false;
				}
				if (type == DownloadDirectoryPolicy.Type.BACKUPS) Preferences.rememberBackupDownloadFolder(name);
				return true;
			});
			preference.setEnabled(Preferences.PREFERENCES.getBoolean(Preferences.KEY_DOWNLOAD_TYPE_FOLDERS, false));
		}
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.download_folder_names), null);
	}
}
