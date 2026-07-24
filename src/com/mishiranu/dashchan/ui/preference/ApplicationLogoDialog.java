package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.List;

public class ApplicationLogoDialog extends DialogFragment {
	private static final String TAG = ApplicationLogoDialog.class.getName();

	public static void show(FragmentManager fragmentManager) {
		if (!LauncherIconManager.arePresetLogosReady() || fragmentManager.findFragmentByTag(TAG) != null) {
			return;
		}
		new ApplicationLogoDialog().show(fragmentManager, TAG);
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
		scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView invitation = new TextView(context);
		invitation.setText(R.string.application_logo_invitation);
		ThemeEngine.applyStyle(invitation);
		invitation.setAutoLinkMask(Linkify.EMAIL_ADDRESSES);
		invitation.setPadding(padding, padding, padding, smallPadding);
		layout.addView(invitation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		TextView requirements = new TextView(context);
		requirements.setText(R.string.application_logo_requirements);
		requirements.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
		ThemeEngine.applyStyle(requirements);
		requirements.setPadding(padding, 0, padding, smallPadding);
		layout.addView(requirements, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		List<LauncherIconManager.LogoOption> options = LauncherIconManager.getLogoOptions();
		String selectedValue = Preferences.getApplicationLogo();
		for (LauncherIconManager.LogoOption option : options) {
			layout.addView(createOptionView(context, option, option.value.equals(selectedValue),
					density, padding, smallPadding));
		}

		return new AlertDialog.Builder(context)
				.setTitle(R.string.application_logo)
				.setView(scrollView)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
	}

	private View createOptionView(Context context, LauncherIconManager.LogoOption option,
			boolean selected, float density, int padding, int smallPadding) {
		LinearLayout row = new LinearLayout(context);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(padding, smallPadding, padding, smallPadding);
		row.setMinimumHeight(Math.round(80f * density));
		ViewUtils.setSelectableItemBackground(row);

		ImageView image = new ImageView(context);
		image.setImageResource(option.iconResId);
		image.setScaleType(ImageView.ScaleType.CENTER_CROP);
		image.setContentDescription(getString(option.titleResId));
		if (option.access == LauncherIconManager.LogoAccess.SUBSCRIBER) {
			image.setColorFilter(Color.BLACK);
		}
		int imageSize = Math.round(64f * density);
		row.addView(image, new LinearLayout.LayoutParams(imageSize, imageSize));

		LinearLayout textLayout = new LinearLayout(context);
		textLayout.setOrientation(LinearLayout.VERTICAL);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		textParams.setMarginStart(padding);
		row.addView(textLayout, textParams);

		TextView title = new TextView(context);
		title.setText(option.titleResId);
		title.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
		ThemeEngine.applyStyle(title);
		textLayout.addView(title);

		int summaryResId = 0;
		switch (option.access) {
			case DVACH_PASS: summaryResId = R.string.application_logo_requires_dvach_pass; break;
			case SUBSCRIBER: summaryResId = R.string.application_logo_paid_only; break;
		}
		if (summaryResId != 0) {
			TextView summary = new TextView(context);
			summary.setText(summaryResId);
			summary.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
			ThemeEngine.applyStyle(summary);
			textLayout.addView(summary);
		}

		RadioButton radioButton = new RadioButton(context);
		ThemeEngine.applyStyle(radioButton);
		radioButton.setChecked(selected);
		radioButton.setClickable(false);
		row.addView(radioButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		row.setOnClickListener(v -> select(option));
		return row;
	}

	private void select(LauncherIconManager.LogoOption option) {
		switch (option.access) {
			case DVACH_PASS: {
				if (!LauncherIconManager.hasDvachPass()) {
					ClickableToast.show(R.string.no_verified_dvach_pass);
					return;
				}
				break;
			}
			case SUBSCRIBER: {
				ClickableToast.show(R.string.application_logo_paid_only);
				return;
			}
		}
		Preferences.setApplicationLogo(option.value);
		LauncherIconManager.apply(requireContext(), Preferences.getApplicationName(), option.value);
		if (getParentFragment() instanceof InterfaceFragment) {
			((InterfaceFragment) getParentFragment()).onApplicationLogoChanged();
		}
		dismiss();
	}
}
