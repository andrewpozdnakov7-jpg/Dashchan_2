package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.CrashReports;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

public final class CrashReportsFragment extends PreferenceFragment {
	private int generation;
	private final Runnable refresh = this::refresh;
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state); CrashReports.listen(refresh);
	}
	@Override public void onResume() { super.onResume(); refresh(); }
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.crash_reports_title), null);
	}
	@Override public void onDestroyView() {
		generation++; CrashReports.unlisten(refresh); super.onDestroyView();
	}
	private void refresh() {
		if (!isAdded() || getView() == null || SecurityStore.locked(requireContext())) return;
		int request = ++generation;
		removeAllPreferences();
		addButton(R.string.crash_report_loading, 0).setEnabled(false);
		CrashReports.execute(() -> {
			ArrayList<File> reports;
			try { reports = CrashReports.list(); } catch (IOException | RuntimeException e) { reports = null; }
			ArrayList<File> result = reports;
			ConcurrentUtils.HANDLER.post(() -> {
				if (!isAdded() || getView() == null || generation != request || SecurityStore.locked(requireContext())) return;
				removeAllPreferences();
				if (result == null || result.isEmpty()) {
					addButton(result == null ? R.string.crash_report_unavailable : R.string.crash_reports_empty, 0).setEnabled(false);
					return;
				}
				for (File file : result) {
					String title = android.text.format.DateFormat.getMediumDateFormat(requireContext()).format(new Date(file.lastModified()))
							+ " " + android.text.format.DateFormat.getTimeFormat(requireContext()).format(new Date(file.lastModified()));
					addButton(title, android.text.format.Formatter.formatShortFileSize(requireContext(), file.length()))
							.setOnClickListener(p -> CrashReportDialogs.view(requireActivity(), file.getName()));
				}
			});
		});
	}
}
