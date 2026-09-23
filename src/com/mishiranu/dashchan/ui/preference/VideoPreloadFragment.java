package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.ui.preference.core.EditPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.Arrays;

public class VideoPreloadFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addCheck(true, Preferences.KEY_VIDEO_PRELOAD, Preferences.DEFAULT_VIDEO_PRELOAD,
				R.string.video_preload_enable, R.string.video_preload_description);
		addList(Preferences.KEY_VIDEO_PRELOAD_NETWORK, Arrays.asList("wifi", "all"),
				Preferences.DEFAULT_VIDEO_PRELOAD_NETWORK, R.string.video_preload_network,
				Arrays.<CharSequence>asList(getString(R.string.wifi_only), getString(R.string.video_preload_all_networks)));
		addSeek(Preferences.KEY_VIDEO_PRELOAD_COUNT, Preferences.DEFAULT_VIDEO_PRELOAD_COUNT,
				getString(R.string.video_preload_count), "%d", null, 1, 5, 1);
		addDialogPreference(new SizePreference(requireContext()));
		addDependency(Preferences.KEY_VIDEO_PRELOAD_NETWORK, Preferences.KEY_VIDEO_PRELOAD, true);
		addDependency(Preferences.KEY_VIDEO_PRELOAD_COUNT, Preferences.KEY_VIDEO_PRELOAD, true);
		addDependency(Preferences.KEY_VIDEO_PRELOAD_SIZE_MB, Preferences.KEY_VIDEO_PRELOAD, true);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.video_preload), null);
	}

	private static final class SizePreference extends DialogPreference<Integer> {
		private static final String STATE_INPUT = "size_input";

		SizePreference(Context context) {
			super(context, Preferences.KEY_VIDEO_PRELOAD_SIZE_MB, Preferences.DEFAULT_VIDEO_PRELOAD_SIZE_MB,
					context.getString(R.string.video_preload_size), p -> context.getString(
							R.string.video_preload_size_format, p.getValue()) + "\n"
							+ context.getString(R.string.video_preload_size_description));
			setDescription(context.getString(R.string.video_preload_size_description));
		}

		@Override
		protected void extract(SharedPreferences preferences) {
			setValue(Math.max(1, Math.min(500, preferences.getInt(key, defaultValue))));
		}

		@Override
		protected void persist(SharedPreferences preferences) {
			// Keep the integer storage used by earlier versions of the size slider.
			preferences.edit().put(key, getValue().intValue()).close();
		}

		@Override
		protected AlertDialog.Builder configureDialog(Bundle savedInstanceState, AlertDialog.Builder builder) {
			Pair<View, LinearLayout> pair = createDialogLayout(builder.getContext());
			SafePasteEditText input = new SafePasteEditText(pair.second.getContext());
			input.setId(android.R.id.edit);
			EditPreference.configureEdit(input, context.getString(R.string.video_preload_size_input),
					InputType.TYPE_CLASS_NUMBER, savedInstanceState != null
							? savedInstanceState.getString(STATE_INPUT, getValue().toString()) : getValue().toString());
			input.setSingleLine(true);
			input.requestFocus();
			input.selectAll();
			pair.second.addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			return super.configureDialog(savedInstanceState, builder).setView(pair.first)
					.setPositiveButton(android.R.string.ok, null);
		}

		@Override
		protected void startDialog(AlertDialog dialog) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
			EditText input = dialog.findViewById(android.R.id.edit);
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
				int value;
				try {
					value = Integer.parseInt(input.getText().toString().trim());
				} catch (NumberFormatException e) {
					value = 0;
				}
				if (value < 1 || value > 500) {
					input.setError(context.getString(R.string.video_preload_size_input));
					return;
				}
				setValue(value);
				dialog.dismiss();
			});
		}

		@Override
		protected void saveState(AlertDialog dialog, Bundle outState) {
			EditText input = dialog.findViewById(android.R.id.edit);
			outState.putString(STATE_INPUT, input.getText().toString());
		}
	}
}
