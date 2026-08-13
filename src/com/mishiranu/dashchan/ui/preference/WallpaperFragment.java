package com.mishiranu.dashchan.ui.preference;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WallpaperManager;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.IOException;

public class WallpaperFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	private void refreshPreferences() {
		removeAllPreferences();
		addHeader(R.string.wallpaper_background);
		String title = Preferences.getWallpaperTitle();
		String current = WallpaperManager.hasActiveWallpaper(requireContext())
				? !StringUtils.isEmpty(title) ? title : getString(R.string.wallpaper_custom)
				: getString(R.string.wallpaper_not_selected);
		addButton(getString(R.string.wallpaper_current), current).setSelectable(false);
		addButton(R.string.wallpaper_select_custom, R.string.wallpaper_select_custom__summary)
				.setOnClickListener(p -> selectCustomWallpaper());
		if (WallpaperManager.hasActiveWallpaper(requireContext())) {
			addButton(R.string.wallpaper_remove, R.string.wallpaper_remove__summary)
					.setOnClickListener(p -> new AlertDialog.Builder(requireContext())
							.setMessage(R.string.wallpaper_remove_confirmation)
							.setPositiveButton(R.string.delete, (dialog, which) -> {
								WallpaperManager.remove(requireContext());
								requireActivity().recreate();
							})
							.setNegativeButton(android.R.string.cancel, null)
							.show());
		}
		addSeek(Preferences.KEY_WALLPAPER_DIM_AMOUNT, Preferences.DEFAULT_WALLPAPER_DIM_AMOUNT,
				R.string.wallpaper_dim, R.string.wallpaper_percent__format, null, 0, 80, 5)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addSeek(Preferences.KEY_WALLPAPER_CARD_OPACITY, Preferences.DEFAULT_WALLPAPER_CARD_OPACITY,
				R.string.wallpaper_card_opacity, R.string.wallpaper_percent__format, null, 50, 100, 5)
				.setOnAfterChangeListener(p -> requireActivity().recreate());

		addHeader(R.string.wallpaper_catalog);
		addButton(R.string.wallpaper_catalog_open, R.string.wallpaper_catalog_open__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new WallpaperCatalogFragment()));
		addButton(R.string.wallpaper_catalog_privacy, R.string.wallpaper_catalog_privacy__summary)
				.setSelectable(false);
	}

	private void selectCustomWallpaper() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("image/*")
				.putExtra("android.content.extra.SHOW_ADVANCED", true)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivityForResult(intent, C.REQUEST_CODE_ATTACH);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == C.REQUEST_CODE_ATTACH && resultCode == Activity.RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				android.content.Context context = requireContext().getApplicationContext();
				ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
					boolean success;
					try {
						WallpaperManager.importCustom(context, uri);
						success = true;
					} catch (IOException | SecurityException e) {
						success = false;
					}
					boolean result = success;
					ConcurrentUtils.HANDLER.post(() -> {
						if (isAdded()) {
							if (result) {
								ClickableToast.show(R.string.wallpaper_installed);
								requireActivity().recreate();
							} else {
								ClickableToast.show(R.string.wallpaper_invalid_image);
							}
						}
					});
				});
			}
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.configure_wallpaper), null);
	}
}
