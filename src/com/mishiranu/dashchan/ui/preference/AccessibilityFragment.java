package com.mishiranu.dashchan.ui.preference;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.ListPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AccessibilityFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addHeader(R.string.font);
		ArrayList<String> fontValues = new ArrayList<>();
		ArrayList<CharSequence> fontEntries = new ArrayList<>();
		fontValues.add(FontManager.FONT_SYSTEM);
		fontEntries.add(getString(R.string.system_font));
		List<FontManager.FontOption> installedFonts = new ArrayList<>();
		List<FontManager.FontOption> catalogFonts = FontManager.getDownloadedCatalogFonts(requireContext());
		installedFonts.addAll(catalogFonts);
		for (FontManager.FontOption option : catalogFonts) {
			fontValues.add(option.id);
			fontEntries.add(option.name + " (" + getString(R.string.downloaded_font) + ")");
		}
		List<FontManager.FontOption> customFonts = FontManager.getCustomFonts(requireContext());
		installedFonts.addAll(customFonts);
		for (FontManager.FontOption option : customFonts) {
			fontValues.add(option.id);
			fontEntries.add(option.name + " (" + getString(R.string.custom_font) + ")");
		}
		String missingCatalogId = FontManager.getSelectedMissingCatalogId(requireContext());
		if (missingCatalogId != null) {
			String missingPreferenceId = FontManager.getCatalogPreferenceId(missingCatalogId);
			for (FontManager.FontOption option : FontManager.getKnownCatalogFonts()) {
				if (option.id.equals(missingPreferenceId)) {
					fontValues.add(option.id);
					fontEntries.add(option.name + " (" + getString(R.string.font_catalog_restoring) + ")");
					break;
				}
			}
		}
		ListPreference fontPreference = addList(Preferences.KEY_APPLICATION_FONT, fontValues,
				FontManager.FONT_SYSTEM, R.string.application_font, fontEntries);
		fontPreference.setOnAfterChangeListener(p -> {
			FontManager.invalidate();
			requireActivity().recreate();
		});
		addButton(R.string.font_catalog, R.string.font_catalog_open__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new FontCatalogFragment()));
		addButton(R.string.install_custom_font, R.string.install_custom_font__summary)
				.setOnClickListener(p -> selectCustomFont());
		Preference<Void> deleteFont = addButton(getString(R.string.delete_installed_font),
				installedFonts.isEmpty() ? getString(R.string.no_installed_fonts)
						: getString(R.string.custom_fonts_count__format, installedFonts.size()));
		deleteFont.setEnabled(!installedFonts.isEmpty());
		deleteFont.setOnClickListener(p -> showDeleteFontDialog(installedFonts));

		addHeader(R.string.appearance);
		String warning = getString(R.string.large_text_layout_warning).replace("%", "%%");
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%")
				+ "\n" + warning;
		addSeek(Preferences.KEY_TEXT_SCALE, Preferences.DEFAULT_TEXT_SCALE,
				getString(R.string.text_scale), scaleFormat, null,
				Preferences.MIN_TEXT_SCALE, Preferences.MAX_TEXT_SCALE, Preferences.STEP_TEXT_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addCheck(true, Preferences.KEY_VOLUME_BUTTONS_TEXT_SCALE, Preferences.DEFAULT_VOLUME_BUTTONS_TEXT_SCALE,
				R.string.volume_buttons_text_scale, R.string.volume_buttons_text_scale__summary);
		addCheck(true, Preferences.KEY_ROUNDED_DIALOGS, Preferences.DEFAULT_ROUNDED_DIALOGS,
				R.string.rounded_dialogs, R.string.rounded_dialogs__summary);
		RoundedDialogsRadiusPreference roundedDialogsRadiusPreference =
				new RoundedDialogsRadiusPreference(requireContext());
		addDialogPreference(roundedDialogsRadiusPreference);
		addDependency(Preferences.KEY_ROUNDED_DIALOGS_RADIUS, Preferences.KEY_ROUNDED_DIALOGS, true);
	}

	private void selectCustomFont() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("*/*")
				.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"font/ttf", "font/otf",
						"application/x-font-ttf", "application/x-font-opentype"})
				.putExtra("android.content.extra.SHOW_ADVANCED", true)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivityForResult(intent, C.REQUEST_CODE_FONT);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == C.REQUEST_CODE_FONT && resultCode == Activity.RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				android.content.Context context = requireContext().getApplicationContext();
				ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
					boolean success;
					try {
						FontManager.importCustomFont(context, uri);
						success = true;
					} catch (IOException | SecurityException e) {
						e.printStackTrace();
						success = false;
					}
					boolean result = success;
					ConcurrentUtils.HANDLER.post(() -> {
						if (isAdded()) {
							if (result) {
								ClickableToast.show(R.string.custom_font_installed);
								requireActivity().recreate();
							} else {
								ClickableToast.show(R.string.custom_font_import_failed);
							}
						}
					});
				});
			}
		}
	}

	private void showDeleteFontDialog(List<FontManager.FontOption> installedFonts) {
		CharSequence[] entries = new CharSequence[installedFonts.size()];
		for (int i = 0; i < installedFonts.size(); i++) {
			entries[i] = installedFonts.get(i).name;
		}
		AlertDialog dialog = new AlertDialog.Builder(requireContext())
				.setTitle(R.string.delete_installed_font)
				.setItems(entries, (listDialog, which) -> {
					AlertDialog confirmationDialog = new AlertDialog.Builder(requireContext())
						.setMessage(getString(R.string.delete_custom_font_confirmation__format, entries[which]))
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(R.string.delete, (confirmation, confirmationWhich) -> {
							if (FontManager.deleteCustomFont(requireContext(), installedFonts.get(which).id)) {
								ClickableToast.show(R.string.custom_font_deleted);
								requireActivity().recreate();
							}
						})
						.create();
					confirmationDialog.setOnShowListener(d -> FontManager.apply(
							confirmationDialog.getWindow().getDecorView()));
					confirmationDialog.show();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> FontManager.apply(dialog.getWindow().getDecorView()));
		dialog.show();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.accessibility), null);
	}
}
