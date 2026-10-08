package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.PhotoEditorBridge;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.MessageDialog;
import java.io.File;

public class ExperimentalFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		if (BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION) {
			addCheck(true, Preferences.KEY_NEW_INTERFACE_MOTION, Preferences.DEFAULT_NEW_INTERFACE_MOTION,
					R.string.new_interface_motion, R.string.new_interface_motion_summary);
		}
		addFormDiagnosticsPreferences();
		addButton(R.string.toolbar_title_sizes, R.string.toolbar_title_sizes__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ToolbarTitleSettingsFragment()));
		addVideoDiagnosticsPreferences();
		addButton(R.string.pip_diagnostic_mode, R.string.pip_diagnostic_hidden_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new PipDiagnosticsFragment()));
		if (PhotoEditorBridge.isAvailable()) {
			addCheck(true, Preferences.KEY_NEW_PHOTO_EDITOR, Preferences.DEFAULT_NEW_PHOTO_EDITOR,
					PhotoEditorBridge.getTitleResId(), PhotoEditorBridge.getSummaryResId());
		}
		addCheck(true, Preferences.KEY_DISCUSSION_CONTEXT, Preferences.DEFAULT_DISCUSSION_CONTEXT,
				R.string.discussion_context, R.string.discussion_context_summary);
		addCheck(true, Preferences.KEY_OUTBOX_JOURNAL, Preferences.DEFAULT_OUTBOX_JOURNAL,
				R.string.outbox_title, R.string.outbox_experimental_summary)
				.setOnAfterChangeListener(p -> refreshPreferences());
		if (Preferences.isOutboxJournalEnabled()) {
			addButton(R.string.outbox_open, R.string.outbox_summary).setOnClickListener(p ->
					((FragmentHandler) requireActivity()).pushFragment(new OutboxFragment()));
		}
		addCheck(true, Preferences.KEY_WINDOWED_THREAD_LOADING,
				Preferences.DEFAULT_WINDOWED_THREAD_LOADING,
				R.string.windowed_thread_loading,
				R.string.windowed_thread_loading__summary);
		CheckPreference wallpaperPreference = addCheck(true, Preferences.KEY_WALLPAPER_ENABLED,
				Preferences.DEFAULT_WALLPAPER_ENABLED, R.string.wallpaper_background,
				R.string.wallpaper_background__summary);
		wallpaperPreference.setOnAfterChangeListener(p -> {
			refreshPreferences();
			requireActivity().recreate();
		});
		if (wallpaperPreference.getValue()) {
			addButton(R.string.configure_wallpaper, R.string.configure_wallpaper__summary)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new WallpaperFragment()));
		}
	}

	private void addFormDiagnosticsPreferences() {
		boolean recording = PostingFormDiagnostics.isRecording();
		boolean saving = PostingFormDiagnostics.isSaving();
		boolean retry = PostingFormDiagnostics.hasPendingExport();
		Preference<Void> capture = addButton(saving ? R.string.video_diagnostics_saving
				: recording || retry ? R.string.form_diagnostics_stop : R.string.form_diagnostics_start,
				R.string.form_diagnostics_summary);
		capture.setEnabled(!saving);
		capture.setOnClickListener(p -> {
			if (PostingFormDiagnostics.isRecording() || PostingFormDiagnostics.hasPendingExport()) {
				PostingFormDiagnostics.stopAndSave(requireContext(), file -> {
					if (!isAdded() || getView() == null) return;
					refreshPreferences();
					if (file != null) {
						new MessageDialog.Builder(requireContext()).setTitle(R.string.form_diagnostics_saved)
								.setMessage(R.string.form_diagnostics_saved_summary)
								.setPositiveButton(R.string.share, (dialog, which) ->
										NavigationUtils.shareFile(requireContext(), file, file.getName()))
								.setNegativeButton(android.R.string.ok, null).show();
					} else ClickableToast.show(R.string.video_diagnostics_save_failed);
				});
				refreshPreferences();
			} else {
				new MessageDialog.Builder(requireContext()).setTitle(R.string.form_diagnostics_start)
						.setMessage(R.string.form_diagnostics_notice)
						.setPositiveButton(R.string.video_diagnostics_begin, (dialog, which) -> {
							PostingFormDiagnostics.start(requireContext());
							refreshPreferences();
						}).setNegativeButton(android.R.string.cancel, null).show();
			}
		});
		File report = PostingFormDiagnostics.getLastFile(requireContext());
		if (!recording && !saving && !retry && report != null) {
			addButton(R.string.form_diagnostics_share, 0).setOnClickListener(p ->
					NavigationUtils.shareFile(requireContext(), report, report.getName()));
		}
	}

	private void addVideoDiagnosticsPreferences() {
		boolean recording = VideoDiagnostics.isRecording();
		boolean saving = VideoDiagnostics.isSaving();
		CheckPreference extendedPreference = addCheck(true, Preferences.KEY_EXTENDED_VIDEO_DIAGNOSTICS,
				false, R.string.video_diagnostics_extended, R.string.video_diagnostics_extended__summary);
		extendedPreference.setEnabled(!recording && !saving);
		Preference<Void> capturePreference = addButton(saving ? getString(R.string.video_diagnostics_saving) : recording
						? getString(R.string.video_diagnostics_stop)
						: getString(R.string.video_diagnostics_start),
				recording ? getString(R.string.video_diagnostics_stop__summary)
						: getString(R.string.video_diagnostics_start__summary));
		capturePreference.setEnabled(!saving);
		capturePreference.setOnClickListener(p -> {
			if (VideoDiagnostics.isRecording()) {
				VideoDiagnostics.stopAsync(file -> {
					if (!isAdded() || getView() == null) return;
					refreshPreferences();
					if (file != null) {
						new MessageDialog.Builder(requireContext())
								.setTitle(R.string.video_diagnostics_saved)
								.setMessage(R.string.video_diagnostics_saved__message)
								.setPositiveButton(R.string.share, (dialog, which) ->
										NavigationUtils.shareFile(requireContext(), file, file.getName()))
								.setNegativeButton(android.R.string.ok, null)
								.show();
					} else {
						ClickableToast.show(R.string.video_diagnostics_save_failed);
					}
				});
				refreshPreferences();
			} else {
				new MessageDialog.Builder(requireContext())
						.setTitle(R.string.video_diagnostics_start)
						.setMessage(getString(R.string.video_diagnostics_privacy_notice)
								+ "\n\n" + getString(R.string.video_diagnostics_memory_notice))
						.setPositiveButton(R.string.video_diagnostics_begin, (dialog, which) -> {
							VideoDiagnostics.start();
							refreshPreferences();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}
		});
		if (!recording && !saving) {
			File lastFile = VideoDiagnostics.getLastFile();
			if (lastFile != null) {
				addButton(R.string.video_diagnostics_share, R.string.video_diagnostics_share__summary)
						.setOnClickListener(p ->
								NavigationUtils.shareFile(requireContext(), lastFile, lastFile.getName()));
				addButton(R.string.video_diagnostics_delete, 0).setOnClickListener(p -> {
					if (VideoDiagnostics.deleteLastFile()) {
						ClickableToast.show(R.string.video_diagnostics_deleted);
						refreshPreferences();
					} else {
						ClickableToast.show(R.string.unknown_error);
					}
				});
			}
		}
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.experimental_features), null);
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshPreferences();
	}

}
