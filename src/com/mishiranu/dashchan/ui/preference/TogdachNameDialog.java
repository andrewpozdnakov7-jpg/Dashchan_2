package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;

public class TogdachNameDialog extends DialogFragment {
	private static final String TAG = TogdachNameDialog.class.getName();

	public static void showIfNeeded(Context context, FragmentManager fragmentManager) {
		if (Preferences.isTogdachNamePrompted()) {
			return;
		}
		boolean updated;
		try {
			PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			updated = packageInfo.lastUpdateTime > packageInfo.firstInstallTime;
		} catch (PackageManager.NameNotFoundException | RuntimeException e) {
			return;
		}
		if (!updated) {
			// This community event is intended for existing users receiving an update, not fresh installs.
			Preferences.setTogdachNamePrompted();
			return;
		}
		if (fragmentManager.findFragmentByTag(TAG) == null) {
			Preferences.setTogdachNamePrompted();
			new TogdachNameDialog().show(fragmentManager, TAG);
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Context context = requireContext();
		float density = ResourceUtils.obtainDensity(context);
		int padding = Math.round(16f * density);
		int smallPadding = Math.round(8f * density);

		ScrollView scrollView = new ScrollView(context);
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(padding, smallPadding, padding, smallPadding);
		scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		ImageView proof = new ImageView(context);
		proof.setImageResource(R.drawable.togdach_name_proof);
		proof.setAdjustViewBounds(true);
		proof.setScaleType(ImageView.ScaleType.FIT_CENTER);
		proof.setContentDescription(context.getString(R.string.togdach_name_proof_description));
		layout.addView(proof, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView message = new TextView(context);
		message.setText(R.string.togdach_name_prompt_message);
		message.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
		ThemeEngine.applyStyle(message);
		message.setPadding(0, padding, 0, smallPadding);
		layout.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.togdach_name_prompt_title)
				.setView(scrollView)
				.setNegativeButton(R.string.keep_current_name, null)
				.setPositiveButton(R.string.use_togdach_name, null)
				.create();
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			if (!LauncherIconManager.apply(context, LauncherIconManager.VALUE_TOGDACH)) {
				ClickableToast.show(R.string.application_name_change_failed);
				return;
			}
			Preferences.setApplicationName(LauncherIconManager.VALUE_TOGDACH);
			dialog.dismiss();
		}));
		return dialog;
	}
}
