package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import chan.content.ChanMarkup;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.preference.core.EditPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;

public class DownloadsFragment extends PreferenceFragment implements FragmentHandler.Callback {
	private static final String EXTRA_IN_STORAGE_REQUEST = "inStorageRequest";
	private Preference<?> downloadUriTreePreference;
	private boolean inStorageRequest;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		inStorageRequest = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_IN_STORAGE_REQUEST);
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addCheck(true, Preferences.KEY_DOWNLOAD_DETAIL_NAME, Preferences.DEFAULT_DOWNLOAD_DETAIL_NAME,
				R.string.detailed_file_name, R.string.detailed_file_name__summary);
		addCheck(true, Preferences.KEY_DOWNLOAD_ORIGINAL_NAME, Preferences.DEFAULT_DOWNLOAD_ORIGINAL_NAME,
				R.string.original_file_name, R.string.original_file_name__summary);
		downloadUriTreePreference = addButton(getString(R.string.download_directory),
				p -> {
					if (Preferences.getDownloadUriTree(requireContext()) == null) {
						return getString(R.string.download_directory_not_selected);
					}
					String name = DataFile.obtain(DataFile.Target.DOWNLOADS, null).getName();
					return !StringUtils.isEmpty(name) ? name
							: getString(R.string.download_directory_not_selected);
				});
		downloadUriTreePreference.setOnClickListener(p -> {
			if (((FragmentHandler) requireActivity()).requestStorage()) {
				inStorageRequest = true;
			}
		});
		addList(Preferences.KEY_DOWNLOAD_CONFLICT_MODE,
				enumList(Preferences.DownloadConflictMode.values(), v -> v.value),
				Preferences.DEFAULT_DOWNLOAD_CONFLICT_MODE.value, R.string.if_file_already_exists,
				enumResList(Preferences.DownloadConflictMode.values(), v -> v.titleResId));
		addList(Preferences.KEY_DOWNLOAD_SUBDIR, enumList(Preferences.DownloadSubdirMode.values(), v -> v.value),
				Preferences.DEFAULT_DOWNLOAD_SUBDIR.value, R.string.show_download_configuration_dialog,
				enumResList(Preferences.DownloadSubdirMode.values(), v -> v.titleResId));
		EditPreference subdirectoryPreference = addEdit(Preferences.KEY_SUBDIR_PATTERN,
				Preferences.DEFAULT_SUBDIR_PATTERN, R.string.subdirectory_pattern, Preferences.DEFAULT_SUBDIR_PATTERN,
				InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		String subdirectoryHtml = IOUtils.readRawResourceString(getResources(), R.raw.markup_subdirectory);
		subdirectoryPreference.setDescription(BUILDER_SUBDIRECTORY.fromHtmlReduced(subdirectoryHtml));
		subdirectoryPreference.setNeutralButton(getString(R.string.more_info),
				() -> showSubdirectoryInfoDialog(getChildFragmentManager()));
		addCheck(true, Preferences.KEY_NOTIFY_DOWNLOAD_COMPLETE, Preferences.DEFAULT_NOTIFY_DOWNLOAD_COMPLETE,
				R.string.notify_when_download_is_completed, R.string.notify_when_download_is_completed__summary);

		addDependency(Preferences.KEY_SUBDIR_PATTERN, Preferences.KEY_DOWNLOAD_SUBDIR, false,
				Preferences.DownloadSubdirMode.DISABLED.value);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.downloads), null);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(EXTRA_IN_STORAGE_REQUEST, inStorageRequest);
	}

	@Override
	public void onStorageRequestResult() {
		if (inStorageRequest) {
			inStorageRequest = false;
			if (downloadUriTreePreference != null) downloadUriTreePreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		downloadUriTreePreference = null;
	}

	private static void showSubdirectoryInfoDialog(FragmentManager fragmentManager) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			String html = IOUtils.readRawResourceString(context.getResources(), R.raw.markup_subdirectory_info);
			return MotionDialogBuilder.standard(context)
					.setTitle(R.string.subdirectory_pattern)
					.setMessage(BUILDER_SUBDIRECTORY.fromHtmlReduced(html))
					.setPositiveButton(android.R.string.ok, null)
					.create();
		});
	}

	private static final ChanMarkup.MarkupBuilder BUILDER_SUBDIRECTORY = new ChanMarkup
			.MarkupBuilder(markup -> markup.addTag("b", ChanMarkup.TAG_BOLD));
}
