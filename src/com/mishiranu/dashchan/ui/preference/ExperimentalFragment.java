package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.File;

public class ExperimentalFragment extends PreferenceFragment {
	private CheckPreference drawOverOtherApplicationsPreference;

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
		addHeader(R.string.additional);
		drawOverOtherApplicationsPreference = addCheck(false, "draw_over_other_applications", false,
				R.string.draw_over_other_applications, R.string.draw_over_other_applications__summary);
		drawOverOtherApplicationsPreference.setValue(Settings.canDrawOverlays(requireContext()));
		drawOverOtherApplicationsPreference.setOnClickListener(p -> {
			try {
				startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
						.setData(Uri.parse("package:" + requireContext().getPackageName())));
			} catch (ActivityNotFoundException e) {
				ClickableToast.show(R.string.unknown_address);
			}
		});
		addCheck(true, Preferences.KEY_USE_GMS_PROVIDER, Preferences.DEFAULT_USE_GMS_PROVIDER,
				R.string.use_gms_security_provider, R.string.use_gms_security_provider__summary);
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
	public void onDestroyView() {
		super.onDestroyView();
		drawOverOtherApplicationsPreference = null;
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
		if (drawOverOtherApplicationsPreference != null) {
			drawOverOtherApplicationsPreference.setValue(Settings.canDrawOverlays(requireContext()));
		}
	}
}
