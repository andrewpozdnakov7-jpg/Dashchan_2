package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import chan.util.CommonUtils;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.BackupManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.ListPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class AboutFragment extends PreferenceFragment implements FragmentHandler.Callback {
	private static final String EXTRA_IN_STORAGE_REQUEST = "inStorageRequest";
	private static final String PROJECT_AUTHOR = "andrewpozdnakov7-jpg";
	private static final String CONTACT_EMAIL = "andrewpozdnakov7@gmail.com";

	private boolean inStorageRequest = false;
	private boolean confirmingBetaChannel = false;

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

		addButton(R.string.statistics, 0)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new StatisticsFragment()));
		addButton(R.string.backup_data, R.string.backup_data__summary)
				.setOnClickListener(p -> new BackupDialog()
						.show(getChildFragmentManager(), BackupDialog.class.getName()));
		addButton(R.string.changelog, 0)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new TextFragment(TextFragment.Type.CHANGELOG)));
		addButton(R.string.privacy_policy, 0)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new TextFragment(TextFragment.Type.PRIVACY_POLICY)));
		if (BuildConfig.ALLOW_APPLICATION_SELF_UPDATE) {
			if (BuildConfig.ALLOW_BETA_UPDATE_CHANNEL) {
				ListPreference updateChannelPreference = addList(Preferences.KEY_UPDATE_CHANNEL,
						enumList(Preferences.UpdateChannel.values(), channel -> channel.value),
						Preferences.DEFAULT_UPDATE_CHANNEL.value, R.string.update_channel,
						enumResList(Preferences.UpdateChannel.values(), channel -> channel.titleResId));
				updateChannelPreference.setOnBeforeChangeListener((preference, value) -> {
					if (!confirmingBetaChannel && Preferences.UpdateChannel.BETA.value.equals(value) &&
							!Preferences.UpdateChannel.BETA.value.equals(preference.getValue())) {
						new AlertDialog.Builder(requireContext())
								.setTitle(R.string.update_channel_beta)
								.setMessage(R.string.beta_update_channel_warning__sentence)
								.setNegativeButton(android.R.string.cancel, null)
								.setPositiveButton(android.R.string.ok, (dialog, which) -> {
									confirmingBetaChannel = true;
									preference.setValue(value);
									confirmingBetaChannel = false;
								})
								.show();
						return false;
					}
					return true;
				});
				updateChannelPreference.setOnAfterChangeListener(preference -> {
					Preferences.resetUpdatePromptState();
					((FragmentHandler) requireActivity()).pushFragment(new UpdateFragment());
				});
			}
			CheckPreference automaticUpdateCheck = addCheck(false, Preferences.KEY_UPDATE_AUTO_CHECK_ENABLED,
					Preferences.isUpdateAutoCheckEnabled(), R.string.automatic_update_check, 0);
			automaticUpdateCheck.setOnAfterChangeListener(preference ->
					Preferences.setUpdateAutoCheckEnabled(preference.getValue()));
			addButton(R.string.check_for_updates, 0)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new UpdateFragment()));
		}
		addButton(getString(R.string.project_author), PROJECT_AUTHOR)
				.setOnClickListener(p -> NavigationUtils.openSystemBrowser(requireContext(),
						Uri.parse("https:" + BuildConfig.GITHUB_URI_METADATA)));
		addButton(R.string.based_on_dashchan, R.string.based_on_dashchan__summary).setSelectable(false);
		Preference<Void> emailPreference = addButton(getString(R.string.contact_email),
				getString(R.string.contact_email__summary, CONTACT_EMAIL));
		emailPreference.setOnClickListener(p -> NavigationUtils.handleUriInternal(requireContext(), null,
				Uri.parse("mailto:" + CONTACT_EMAIL)));
		emailPreference.setOnLongClickListener(p -> {
			StringUtils.copyToClipboard(requireContext(), CONTACT_EMAIL);
			ClickableToast.show(R.string.email_address_copied);
		});
		addButton(R.string.foss_licenses, R.string.foss_licenses__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new TextFragment(TextFragment.Type.LICENSES)));
		String versionDate = TextFragment.formatChangelogDate
				(DateFormat.getDateFormat(requireContext()), BuildConfig.VERSION_DATE);
		addButton(getString(R.string.version), BuildConfig.VERSION_NAME +
				(versionDate != null ? " " + versionDate : ""));
		String webViewProvider = WebViewUtils.getProviderSummary(requireContext());
		addButton("WebView", webViewProvider != null ? webViewProvider : "-").setSelectable(false);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.about), null);
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
			if (Preferences.getDownloadUriTree(requireContext()) != null) {
				restoreBackup();
			}
		}
	}

	private void restoreBackup() {
		if (Preferences.getDownloadUriTree(requireContext()) == null) {
			if (((FragmentHandler) requireActivity()).requestStorage()) {
				inStorageRequest = true;
			}
		} else {
			List<BackupManager.BackupFile> backupFiles = BackupManager.getAvailableBackups(requireContext());
			if (backupFiles != null && !backupFiles.isEmpty()) {
				RestoreListDialog dialog = new RestoreListDialog(backupFiles);
				dialog.show(getChildFragmentManager(), RestoreListDialog.class.getName());
			} else {
				ClickableToast.show(R.string.backups_not_found);
			}
		}
	}

	public static class BackupDialog extends DialogFragment implements DialogInterface.OnClickListener {
		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			String[] items = {getString(R.string.save_data), getString(R.string.restore_data)};
			return new AlertDialog.Builder(requireContext())
					.setItems(items, this)
					.setNegativeButton(android.R.string.cancel, null)
					.create();
		}

		@Override
		public void onClick(DialogInterface dialog, int which) {
			if (which == 0) {
				new BackupTaskDialog(BackupTaskDialog.Mode.CREATE, null, null)
						.show(getParentFragmentManager(), BackupTaskDialog.class.getName());
			} else if (which == 1) {
				((AboutFragment) getParentFragment()).restoreBackup();
			}
		}
	}

	public static class RestoreListDialog extends DialogFragment {
		private static final String EXTRA_FILES = "files";
		private static final String EXTRA_NAMES = "names";

		public RestoreListDialog() {}

		public RestoreListDialog(List<BackupManager.BackupFile> backupFiles) {
			Bundle args = new Bundle();
			ArrayList<String> files = new ArrayList<>(backupFiles.size());
			ArrayList<String> names = new ArrayList<>(backupFiles.size());
			for (BackupManager.BackupFile backupFile : backupFiles) {
				files.add(backupFile.file.getRelativePath());
				names.add(backupFile.name);
			}
			args.putStringArrayList(EXTRA_FILES, files);
			args.putStringArrayList(EXTRA_NAMES, names);
			setArguments(args);
		}

		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			ArrayList<String> names = requireArguments().getStringArrayList(EXTRA_NAMES);
			String[] items = CommonUtils.toArray(names, String.class);
			return new AlertDialog.Builder(requireContext())
					.setTitle(R.string.restore_data)
					.setItems(items, (d, which) -> {
						String path = requireArguments().getStringArrayList(EXTRA_FILES).get(which);
						new BackupTaskDialog(BackupTaskDialog.Mode.READ, path, null)
								.show(getParentFragmentManager(), BackupTaskDialog.class.getName());
					})
					.setNegativeButton(android.R.string.cancel, null)
					.create();
		}
	}

	public static class RestoreEntriesDialog extends DialogFragment {
		private static final String EXTRA_FILE = "file";
		private static final String EXTRA_ENTRIES = "entries";
		private static final String EXTRA_CHECKED = "checked";

		public RestoreEntriesDialog() {}

		public RestoreEntriesDialog(DataFile file, List<BackupManager.Entry> entries) {
			Bundle args = new Bundle();
			ArrayList<String> entryNames = new ArrayList<>();
			for (BackupManager.Entry entry : entries) {
				entryNames.add(entry.name());
			}
			args.putString(EXTRA_FILE, file.getRelativePath());
			args.putStringArrayList(EXTRA_ENTRIES, entryNames);
			setArguments(args);
		}

		private boolean[] checkedItems;

		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			ArrayList<String> entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES);
			String[] items = new String[entryNames.size()];
			for (int i = 0; i < items.length; i++) {
				items[i] = getString(BackupManager.Entry.valueOf(entryNames.get(i)).titleResId);
			}
			checkedItems = new boolean[items.length];
			ArrayList<String> checked = savedInstanceState != null
					? savedInstanceState.getStringArrayList(EXTRA_CHECKED) : null;
			for (int i = 0; i < checkedItems.length; i++) {
				checkedItems[i] = checked == null || checked.contains(entryNames.get(i));
			}
			return new AlertDialog.Builder(requireContext())
					.setTitle(R.string.restore_data)
					.setMultiChoiceItems(items, checkedItems,
							(d, which, isChecked) -> checkedItems[which] = isChecked)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (d, w) -> loadBackup())
					.create();
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);

			ArrayList<String> entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES);
			ArrayList<String> checked = new ArrayList<>();
			for (int i = 0; i < checkedItems.length; i++) {
				if (checkedItems[i]) {
					checked.add(entryNames.get(i));
				}
			}
			outState.putStringArrayList(EXTRA_CHECKED, checked);
		}

		private void loadBackup() {
			ArrayList<String> entryNames = requireArguments().getStringArrayList(EXTRA_ENTRIES);
			HashSet<BackupManager.Entry> checked = new HashSet<>();
			for (int i = 0; i < checkedItems.length; i++) {
				if (checkedItems[i]) {
					checked.add(BackupManager.Entry.valueOf(entryNames.get(i)));
				}
			}
			if (!checked.isEmpty()) {
				String path = requireArguments().getString(EXTRA_FILE);
				new BackupTaskDialog(BackupTaskDialog.Mode.RESTORE, path, checked)
						.show(getParentFragmentManager(), BackupTaskDialog.class.getName());
			}
		}
	}

	public static class BackupTaskDialog extends DialogFragment {
		private static final String EXTRA_MODE = "mode";
		private static final String EXTRA_FILE = "file";
		private static final String EXTRA_ENTRIES = "entries";

		public enum Mode {CREATE, READ, RESTORE}

		public BackupTaskDialog() {}

		public BackupTaskDialog(Mode mode, String path, Iterable<BackupManager.Entry> entries) {
			Bundle args = new Bundle();
			args.putString(EXTRA_MODE, mode.name());
			args.putString(EXTRA_FILE, path);
			if (entries != null) {
				ArrayList<String> names = new ArrayList<>();
				for (BackupManager.Entry entry : entries) names.add(entry.name());
				args.putStringArrayList(EXTRA_ENTRIES, names);
			}
			setArguments(args);
		}

		@NonNull
		@Override
		public ProgressDialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = new ProgressDialog(requireContext(), null);
			dialog.setMessage(getString(R.string.processing_data__ellipsis));
			dialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(android.R.string.cancel),
					(d, which) -> dismiss());
			return dialog;
		}

		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			Bundle args = requireArguments();
			Mode mode = Mode.valueOf(args.getString(EXTRA_MODE));
			String path = args.getString(EXTRA_FILE);
			HashSet<BackupManager.Entry> entries = new HashSet<>();
			ArrayList<String> names = args.getStringArrayList(EXTRA_ENTRIES);
			if (names != null) {
				for (String name : names) entries.add(BackupManager.Entry.valueOf(name));
			}
			BackupViewModel viewModel = new ViewModelProvider(this).get(BackupViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				BackupTask task = new BackupTask(viewModel, requireContext().getApplicationContext(),
						mode, path, entries);
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismissAllowingStateLoss();
				switch (mode) {
					case CREATE: {
						DownloadService.Binder binder = ((FragmentHandler) requireActivity()).getDownloadBinder();
						if (result.input != null && binder != null) {
							BackupManager.saveBackup(binder, result.input);
						} else {
							IOUtils.close(result.input);
							ClickableToast.show(R.string.no_access);
						}
						break;
					}
					case READ: {
						if (result.entries == null || result.entries.isEmpty()) {
							ClickableToast.show(R.string.invalid_data_format);
						} else {
							DataFile file = DataFile.obtain(DataFile.Target.DOWNLOADS, path);
							new RestoreEntriesDialog(file, result.entries).show(getParentFragmentManager(),
									RestoreEntriesDialog.class.getName());
						}
						break;
					}
					case RESTORE: {
						if (result.success) {
							NavigationUtils.restartApplication(requireContext());
						} else {
							ClickableToast.show(R.string.unknown_error);
						}
						break;
					}
				}
				viewModel.markHandled();
			});
		}
	}

	private static class BackupResult {
		public final InputStream input;
		public final List<BackupManager.Entry> entries;
		public final boolean success;

		public BackupResult(InputStream input, List<BackupManager.Entry> entries, boolean success) {
			this.input = input;
			this.entries = entries;
			this.success = success;
		}
	}

	private static class BackupTask extends ExecutorTask<Void, BackupResult> {
		private final BackupViewModel viewModel;
		private final Context context;
		private final BackupTaskDialog.Mode mode;
		private final String path;
		private final HashSet<BackupManager.Entry> entries;

		public BackupTask(BackupViewModel viewModel, Context context, BackupTaskDialog.Mode mode,
				String path, HashSet<BackupManager.Entry> entries) {
			this.viewModel = viewModel;
			this.context = context;
			this.mode = mode;
			this.path = path;
			this.entries = entries;
		}

		@Override
		protected BackupResult run() {
			switch (mode) {
				case CREATE: {
					InputStream input = BackupManager.makeBackup(context);
					return new BackupResult(input, null, input != null);
				}
				case READ: {
					DataFile file = DataFile.obtain(DataFile.Target.DOWNLOADS, path);
					return new BackupResult(null, BackupManager.readBackupEntries(file), false);
				}
				case RESTORE: {
					DataFile file = DataFile.obtain(DataFile.Target.DOWNLOADS, path);
					return new BackupResult(null, null, BackupManager.loadBackup(file, entries));
				}
				default: {
					throw new IllegalStateException();
				}
			}
		}

		@Override
		protected void onCancel(BackupResult result) {
			if (result != null) IOUtils.close(result.input);
		}

		@Override
		protected void onComplete(BackupResult result) {
			viewModel.handleResult(result);
		}
	}

	public static class BackupViewModel extends TaskViewModel<BackupTask, BackupResult> {
		private BackupResult pending;

		@Override
		public void handleResult(BackupResult result) {
			pending = result;
			super.handleResult(result);
		}

		public void markHandled() {
			pending = null;
		}

		@Override
		protected void onCleared() {
			super.onCleared();
			if (pending != null) IOUtils.close(pending.input);
			pending = null;
		}
	}
}
