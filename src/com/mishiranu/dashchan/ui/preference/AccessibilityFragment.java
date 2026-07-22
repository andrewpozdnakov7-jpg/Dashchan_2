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
		for (FontManager.FontOption option : FontManager.getBuiltInFonts()) {
			fontValues.add(option.id);
			fontEntries.add(option.name);
		}
		List<FontManager.FontOption> customFonts = FontManager.getCustomFonts(requireContext());
		for (FontManager.FontOption option : customFonts) {
			fontValues.add(option.id);
			fontEntries.add(option.name + " (" + getString(R.string.custom_font) + ")");
		}
		ListPreference fontPreference = addList(Preferences.KEY_APPLICATION_FONT, fontValues,
				FontManager.FONT_SYSTEM, R.string.application_font, fontEntries);
		fontPreference.setOnAfterChangeListener(p -> {
			FontManager.invalidate();
			requireActivity().recreate();
		});
		addButton(R.string.install_custom_font, R.string.install_custom_font__summary)
				.setOnClickListener(p -> selectCustomFont());
		Preference<Void> deleteFont = addButton(getString(R.string.delete_custom_font),
				customFonts.isEmpty() ? getString(R.string.no_custom_fonts)
						: getString(R.string.custom_fonts_count__format, customFonts.size()));
		deleteFont.setEnabled(!customFonts.isEmpty());
		deleteFont.setOnClickListener(p -> showDeleteFontDialog(customFonts));

		addHeader(R.string.appearance);
		String warning = getString(R.string.large_text_layout_warning).replace("%", "%%");
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%")
				+ "\n" + warning;
		addSeek(Preferences.KEY_TEXT_SCALE, Preferences.DEFAULT_TEXT_SCALE,
				getString(R.string.text_scale), scaleFormat, null,
				Preferences.MIN_TEXT_SCALE, Preferences.MAX_TEXT_SCALE, Preferences.STEP_TEXT_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
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

	private void showDeleteFontDialog(List<FontManager.FontOption> customFonts) {
		CharSequence[] entries = new CharSequence[customFonts.size()];
		for (int i = 0; i < customFonts.size(); i++) {
			entries[i] = customFonts.get(i).name;
		}
		AlertDialog dialog = new AlertDialog.Builder(requireContext())
				.setTitle(R.string.delete_custom_font)
				.setItems(entries, (listDialog, which) -> {
					AlertDialog confirmationDialog = new AlertDialog.Builder(requireContext())
						.setMessage(getString(R.string.delete_custom_font_confirmation__format, entries[which]))
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(R.string.delete, (confirmation, confirmationWhich) -> {
							if (FontManager.deleteCustomFont(requireContext(), customFonts.get(which).id)) {
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
