package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.AppDiagnostics;
import com.mishiranu.dashchan.util.DiagnosticReportSharing;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.MessageDialog;
import java.io.File;

/** One explicit capture control, before the unchanged crash-report settings. */
public class DiagnosticsFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState); refreshPreferences();
	}
	private void refreshPreferences() {
		if (getView() == null) return;
		removeAllPreferences();
		addAppDiagnosticsPreferences();
		addHeader(R.string.crash_reports_title);
		addCheck(true, Preferences.KEY_CRASH_REPORT_PROMPTS, Preferences.DEFAULT_CRASH_REPORT_PROMPTS,
				R.string.crash_report_prompt_enabled, R.string.crash_report_prompt_summary);
		addButton(R.string.crash_reports_saved, R.string.crash_reports_saved_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new CrashReportsFragment()));
		addButton(R.string.crash_test_title, R.string.crash_test_summary)
				.setOnClickListener(p -> CrashReportDialogs.confirmTest(requireActivity()));
	}
	private void addAppDiagnosticsPreferences() {
		boolean recording = AppDiagnostics.isRecording(), saving = AppDiagnostics.isSaving(), retry = AppDiagnostics.hasPendingExport();
		Preference<Void> capture = addButton(saving ? R.string.video_diagnostics_saving
				: recording ? R.string.video_diagnostics_stop : retry ? R.string.app_diagnostics_retry : R.string.video_diagnostics_start,
				recording ? R.string.video_diagnostics_stop__summary : R.string.app_diagnostics_summary);
		capture.setEnabled(!saving);
		capture.setOnClickListener(p -> {
			if (AppDiagnostics.isRecording() || AppDiagnostics.hasPendingExport()) {
				AppDiagnostics.stopAndSave(requireContext(), file -> {
					if (!isAdded() || getView() == null) return;
					refreshPreferences();
					if (file != null) {
						new MessageDialog.Builder(requireContext()).setTitle(R.string.video_diagnostics_saved)
								.setMessage(R.string.video_diagnostics_saved__message)
								.setPositiveButton(R.string.share, (dialog, which) -> DiagnosticReportSharing.share(requireActivity(), file))
								.setNegativeButton(android.R.string.ok, null).show();
					} else ClickableToast.show(R.string.video_diagnostics_save_failed);
				});
				refreshPreferences();
			} else {
				new MessageDialog.Builder(requireContext()).setTitle(R.string.video_diagnostics_start)
						.setMessage(getString(R.string.app_diagnostics_notice) + "\n\n" + getString(R.string.video_diagnostics_memory_notice))
						.setPositiveButton(R.string.video_diagnostics_begin, (dialog, which) -> {
							if (!AppDiagnostics.start(requireContext())) ClickableToast.show(R.string.video_diagnostics_save_failed);
							refreshPreferences();
						}).setNegativeButton(android.R.string.cancel, null).show();
			}
		});
		File report = AppDiagnostics.getLastFile(requireContext());
		if (!recording && !saving && !retry && report != null) {
			addButton(R.string.video_diagnostics_share, R.string.video_diagnostics_share__summary)
					.setOnClickListener(p -> DiagnosticReportSharing.share(requireActivity(), report));
			addButton(R.string.video_diagnostics_delete, 0).setOnClickListener(p -> {
				if (AppDiagnostics.deleteLastFile(requireContext())) {
					ClickableToast.show(R.string.video_diagnostics_deleted); refreshPreferences();
				} else ClickableToast.show(R.string.unknown_error);
			});
		}
	}
	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.settings_diagnostics), null);
	}
	@Override public void onResume() { super.onResume(); refreshPreferences(); }
}
