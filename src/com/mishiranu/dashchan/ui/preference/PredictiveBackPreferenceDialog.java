package com.mishiranu.dashchan.ui.preference;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;

/** Pending choices survive rotation; closing either dialog never writes preferences. */
public class PredictiveBackPreferenceDialog extends DialogFragment {
	static final String RESULT = "predictive_back_choice";
	static final String CHOICE = "choice";
	static final int ENABLE_NEW_INTERFACE = 1, ENABLE_OLD_INTERFACE = 2, DISABLE_NEW_INTERFACE = 3;
	private static final String MODE = "mode";

	static void show(FragmentManager manager, int mode) {
		String tag = PredictiveBackPreferenceDialog.class.getName() + "/" + mode;
		if (manager.isStateSaved() || manager.findFragmentByTag(tag) != null) return;
		PredictiveBackPreferenceDialog dialog = new PredictiveBackPreferenceDialog();
		Bundle arguments = new Bundle();
		arguments.putInt(MODE, mode);
		dialog.setArguments(arguments);
		dialog.show(manager, tag);
	}

	@NonNull @Override public Dialog onCreateDialog(Bundle state) {
		int mode = requireArguments().getInt(MODE);
		android.app.AlertDialog.Builder builder = MotionDialogBuilder.standard(requireContext())
				.setNegativeButton(android.R.string.cancel, null);
		switch (mode) {
			case ENABLE_NEW_INTERFACE:
				return builder.setTitle(R.string.predictive_back_enable_title)
						.setMessage(R.string.predictive_back_enable_design_message)
						.setNeutralButton(R.string.predictive_back_keep_old_interface,
								(dialog, which) -> showLegacyWarning())
						.setPositiveButton(R.string.predictive_back_enable,
								(dialog, which) -> confirm(ENABLE_NEW_INTERFACE)).create();
			case ENABLE_OLD_INTERFACE:
				return builder.setTitle(R.string.predictive_back_old_interface_title)
						.setMessage(R.string.predictive_back_old_interface_warning)
						.setPositiveButton(R.string.predictive_back_enable_anyway,
								(dialog, which) -> confirm(ENABLE_OLD_INTERFACE)).create();
			case DISABLE_NEW_INTERFACE:
				return builder.setTitle(R.string.predictive_back_disable_design_title)
						.setMessage(R.string.predictive_back_disable_design_message)
						.setPositiveButton(R.string.predictive_back_disable,
								(dialog, which) -> confirm(DISABLE_NEW_INTERFACE)).create();
			default:
				throw new IllegalArgumentException("Unknown predictive Back dialog mode");
		}
	}

	private void confirm(int choice) {
		if (!isAdded() || isRemoving()) return;
		FragmentManager manager = getParentFragmentManager();
		if (manager.isStateSaved()) return;
		// Applying the choice can recreate the Activity. Remove this fragment synchronously
		// first, so its old prompt cannot be included in the restored fragment state.
		dismissNow();
		Bundle result = new Bundle();
		result.putInt(CHOICE, choice);
		manager.setFragmentResult(RESULT, result);
	}

	private void showLegacyWarning() {
		if (!isAdded() || isRemoving()) return;
		FragmentManager manager = getParentFragmentManager();
		if (manager.isStateSaved()) return;
		dismissNow();
		show(manager, ENABLE_OLD_INTERFACE);
	}
}
