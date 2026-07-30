package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.SeekPreference;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
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
		CheckPreference hardwareAccelerationPreference = addCheck(true,
				Preferences.KEY_HARDWARE_VIDEO_ACCELERATION,
				Preferences.DEFAULT_HARDWARE_VIDEO_ACCELERATION,
				R.string.hardware_video_acceleration, R.string.hardware_video_acceleration__summary);
		hardwareAccelerationPreference.setOnAfterChangeListener(p -> {
			if (!p.getValue() && VideoDiagnostics.isRecording()) {
				VideoDiagnostics.stop();
			}
			refreshPreferences();
		});
		if (hardwareAccelerationPreference.getValue()) {
			addVideoDiagnosticsPreferences();
		}
		addCheck(true, Preferences.KEY_IMAGE_EDITOR, Preferences.DEFAULT_IMAGE_EDITOR,
				R.string.image_editor, R.string.image_editor__summary);
		CheckPreference audioBoostPreference = addCheck(true, Preferences.KEY_VIDEO_AUDIO_BOOST,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST, R.string.video_audio_boost,
				R.string.video_audio_boost__summary);
		SeekPreference audioBoostLevelPreference = addSeek(Preferences.KEY_VIDEO_AUDIO_BOOST_DB,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST_DB, R.string.video_audio_boost_level,
				R.string.video_audio_boost_level__format, null, Preferences.MIN_VIDEO_AUDIO_BOOST_DB,
				Preferences.MAX_VIDEO_AUDIO_BOOST_DB, 3);
		audioBoostLevelPreference.setEnabled(audioBoostPreference.getValue());
		audioBoostPreference.setOnAfterChangeListener(p -> refreshPreferences());
		addHeader(R.string.additional);
		if (BuildConfig.ALLOW_GMS_SECURITY_PROVIDER) {
			addCheck(true, Preferences.KEY_USE_GMS_PROVIDER, Preferences.DEFAULT_USE_GMS_PROVIDER,
					R.string.use_gms_security_provider, R.string.use_gms_security_provider__summary);
		}
	}

	private void addVideoDiagnosticsPreferences() {
		boolean recording = VideoDiagnostics.isRecording();
		Preference<Void> capturePreference = addButton(recording
						? getString(R.string.video_diagnostics_stop)
						: getString(R.string.video_diagnostics_start),
				recording ? getString(R.string.video_diagnostics_stop__summary)
						: getString(R.string.video_diagnostics_start__summary));
		capturePreference.setOnClickListener(p -> {
			if (VideoDiagnostics.isRecording()) {
				File file = VideoDiagnostics.stop();
				refreshPreferences();
				if (file != null) {
					new AlertDialog.Builder(requireContext())
							.setTitle(R.string.video_diagnostics_saved)
							.setMessage(R.string.video_diagnostics_saved__message)
							.setPositiveButton(R.string.share, (dialog, which) ->
									NavigationUtils.shareFile(requireContext(), file, file.getName()))
							.setNegativeButton(android.R.string.ok, null)
							.show();
				} else {
					ClickableToast.show(R.string.video_diagnostics_save_failed);
				}
			} else {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.video_diagnostics_start)
						.setMessage(R.string.video_diagnostics_privacy_notice)
						.setPositiveButton(R.string.video_diagnostics_start, (dialog, which) -> {
							VideoDiagnostics.start();
							refreshPreferences();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}
		});
		if (!recording) {
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
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.experimental_features), null);
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshPreferences();
	}

}
