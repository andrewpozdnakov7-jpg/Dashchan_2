package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.update.WhatsNewData;
import com.mishiranu.dashchan.ui.FragmentHandler;

public class WhatsNewDialog extends DialogFragment {
	private static final String TAG = WhatsNewDialog.class.getName();

	public static void show(FragmentManager fragmentManager) {
		if (fragmentManager.findFragmentByTag(TAG) == null) {
			new WhatsNewDialog().show(fragmentManager, TAG);
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		WhatsNewData.Release release = WhatsNewData.readCurrent(requireContext());
		String versionName = release != null ? release.name : BuildConfig.VERSION_NAME;
		CharSequence message = release != null ? formatChanges(release.text)
				: getText(R.string.whats_new_unavailable);
		return new AlertDialog.Builder(requireContext())
				.setTitle(getString(R.string.whats_new_title__format, versionName))
				.setMessage(message)
				.setNeutralButton(R.string.changelog, (dialog, which) ->
						((FragmentHandler) requireActivity())
								.pushFragment(new TextFragment(TextFragment.Type.CHANGELOG)))
				.setPositiveButton(R.string.got_it, null)
				.create();
	}

	private static CharSequence formatChanges(String text) {
		StringBuilder builder = new StringBuilder();
		for (String sourceLine : text.replace("\r", "").split("\n")) {
			String line = sourceLine.trim();
			if (line.startsWith("*")) {
				line = line.substring(1).trim();
			}
			if (!line.isEmpty()) {
				if (builder.length() > 0) {
					builder.append("\n\n");
				}
				builder.append("\u2022 ").append(line);
			}
		}
		return builder;
	}
}
