package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
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

public class ToolbarTitleSettingsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.toolbar_title_sizes), null);
	}

	private void refreshPreferences() {
		if (getView() == null) return;
		removeAllPreferences();
		addCheck(true, Preferences.KEY_TOOLBAR_TITLE_CUSTOMIZATION,
				Preferences.DEFAULT_TOOLBAR_TITLE_CUSTOMIZATION, R.string.toolbar_title_customization,
				R.string.toolbar_title_customization__summary).setOnAfterChangeListener(p -> refreshPreferences());
		if (!Preferences.isToolbarTitleCustomizationEnabled()) return;
		addCheck(true, Preferences.KEY_TOOLBAR_TITLE_HYPHENATION, false,
				R.string.toolbar_title_hyphenation, R.string.toolbar_title_hyphenation__summary);
		addCheck(true, Preferences.KEY_TOOLBAR_ADAPTIVE_TITLE, true, R.string.toolbar_title_adaptive,
				R.string.toolbar_title_sizes_description).setOnAfterChangeListener(p -> refreshPreferences());
		addSize(Preferences.KEY_TOOLBAR_TITLE_SIZE, R.string.toolbar_title_size_normal);
		if (Preferences.isToolbarTitleAdaptive()) {
			addSize(Preferences.KEY_TOOLBAR_MIN_TITLE_SIZE, R.string.toolbar_title_size_minimum);
			addSize(Preferences.KEY_TOOLBAR_TITLE_SIZE_STEP, R.string.toolbar_title_size_step);
		} else {
			addSize(Preferences.KEY_TOOLBAR_COMPACT_TITLE_SIZE, R.string.toolbar_title_size_compact);
		}
		addButton(R.string.restore_defaults, 0).setOnClickListener(p -> {
			Preferences.resetToolbarTitleSizes();
			refreshPreferences();
		});
	}

	private void addSize(String key, int title) {
		SizePreference preference = new SizePreference(requireContext(), key, title);
		addDialogPreference(preference);
		preference.setOnAfterChangeListener(p -> refreshPreferences());
	}

	private static float sizeForKey(String key) {
		if (Preferences.KEY_TOOLBAR_MIN_TITLE_SIZE.equals(key)) return Preferences.getToolbarMinTitleSize();
		if (Preferences.KEY_TOOLBAR_TITLE_SIZE_STEP.equals(key)) return Preferences.getToolbarTitleSizeStep();
		return Preferences.getToolbarTitleSize(Preferences.KEY_TOOLBAR_COMPACT_TITLE_SIZE.equals(key));
	}

	private static final class SizePreference extends DialogPreference<Float> {
		SizePreference(Context context, String key, int title) {
			super(context, key, sizeForKey(key), context.getString(title), p -> p.getValue() + " sp");
		}

		@Override
		protected void extract(SharedPreferences preferences) { setValue(sizeForKey(key)); }

		@Override
		protected void persist(SharedPreferences preferences) {
			float normal = Preferences.KEY_TOOLBAR_TITLE_SIZE.equals(key) ? getValue()
					: Preferences.getToolbarTitleSize(false);
			float compact = Preferences.KEY_TOOLBAR_COMPACT_TITLE_SIZE.equals(key) ? getValue()
					: Preferences.getToolbarTitleSize(true);
			float minimum = Preferences.KEY_TOOLBAR_MIN_TITLE_SIZE.equals(key) ? getValue()
					: Preferences.getToolbarMinTitleSize();
			float step = Preferences.KEY_TOOLBAR_TITLE_SIZE_STEP.equals(key) ? getValue()
					: Preferences.getToolbarTitleSizeStep();
			Preferences.setToolbarTitleSizes(normal, Math.min(normal, compact), Preferences.isToolbarTitleAdaptive(),
					Math.min(normal, minimum), step);
		}

		@Override
		protected AlertDialog.Builder configureDialog(Bundle savedInstanceState, AlertDialog.Builder builder) {
			Pair<View, LinearLayout> pair = createDialogLayout(builder.getContext());
			SafePasteEditText input = new SafePasteEditText(pair.second.getContext());
			input.setId(android.R.id.edit);
			EditPreference.configureEdit(input, "sp", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
					savedInstanceState != null ? savedInstanceState.getString("input", getValue().toString())
							: getValue().toString());
			input.setSingleLine(true);
			input.requestFocus();
			input.selectAll();
			pair.second.addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			return super.configureDialog(savedInstanceState, builder).setView(pair.first)
					.setPositiveButton(android.R.string.ok, null);
		}

		@Override
		protected void startDialog(AlertDialog dialog) {
			EditText input = dialog.findViewById(android.R.id.edit);
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
				float value;
				try { value = Float.parseFloat(input.getText().toString().trim().replace(',', '.')); }
				catch (NumberFormatException e) { value = 0f; }
				boolean step = Preferences.KEY_TOOLBAR_TITLE_SIZE_STEP.equals(key);
				float maximum = step ? 10f : Preferences.KEY_TOOLBAR_TITLE_SIZE.equals(key)
						? Preferences.MAX_TOOLBAR_TITLE_SIZE : Preferences.getToolbarTitleSize(false);
				if (!(value >= (step ? 1f : Preferences.MIN_TOOLBAR_TITLE_SIZE) && value <= maximum)) {
					input.setError(context.getString(step ? R.string.toolbar_title_step_error : R.string.toolbar_title_sizes_error));
					return;
				}
				setValue(value);
				dialog.dismiss();
			});
		}

		@Override
		protected void saveState(AlertDialog dialog, Bundle outState) {
			EditText input = dialog.findViewById(android.R.id.edit);
			outState.putString("input", input.getText().toString());
		}
	}
}
