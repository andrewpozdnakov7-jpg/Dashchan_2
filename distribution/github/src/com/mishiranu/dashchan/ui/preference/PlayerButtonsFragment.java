package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.PlayerButtonsLayout.Button;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.SurfaceMotion;
import com.mishiranu.dashchan.widget.ThemeEngine;

/** Only the arrangement editor. The runtime player and its storage contract are unchanged. */
public class PlayerButtonsFragment extends PreferenceFragment {
	private AlertDialog columnsDialog;
	private android.app.AlertDialog helpDialog;
	private PlayerButtonsEditorView editor;
	private PlayerButtonsToolbarAppearance toolbarAppearance;

	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		addPreference(new EditorPreference(), false);
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.player_buttons_layout), null);
	}
	@Override public void onStart() {
		super.onStart();
		restoreToolbar();
		toolbarAppearance = new PlayerButtonsToolbarAppearance(((FragmentHandler) requireActivity()).getToolbarView(),
				new PlayerButtonsColors(requireContext()));
	}
	private void restoreToolbar() {
		if (toolbarAppearance != null) { toolbarAppearance.restore(); toolbarAppearance = null; }
	}
	@Override public void onStop() {
		if (editor != null) editor.finishVisuals();
		dismissColumns();
		dismissHelp(); restoreToolbar();
		super.onStop();
	}
	@Override public void onDestroyView() {
		if (editor != null) editor.finishVisuals();
		editor = null;
		dismissColumns();
		dismissHelp(); restoreToolbar();
		super.onDestroyView();
	}
	private void dismissColumns() {
		if (columnsDialog != null) { columnsDialog.dismiss(); columnsDialog = null; }
	}
	private void dismissHelp() {
		if (helpDialog != null) { helpDialog.dismiss(); helpDialog = null; }
	}
	private void showHelp() {
		if (editor == null || editor.isDragging()) return;
		dismissHelp();
		// Existing themed dialog component; this only explains the already implemented gestures.
		helpDialog = new com.mishiranu.dashchan.content.security.SecureDialogBuilder(requireContext())
				.setTitle(R.string.player_buttons_help_title).setMessage(R.string.player_buttons_help_body)
				.setPositiveButton(R.string.player_buttons_help_close, null).create();
		helpDialog.show();
	}
	private void chooseColumns() {
		if (editor == null || editor.isDragging()) return;
		editor.finishVisuals();
		dismissColumns();
		Context host = requireContext();
		PlayerButtonsColors colors = new PlayerButtonsColors(host);
		Context themed = new ContextThemeWrapper(host, ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.LIGHT
				? R.style.Theme_Slooop_StartupNotice_Light : R.style.Theme_Slooop_StartupNotice_Dark);
		String[] choices = {"1", "2", "3", "4", "5", "6"};
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(themed,
				android.R.layout.simple_list_item_single_choice, choices) {
			@Override public View getView(int position, View convertView, ViewGroup parent) {
				CheckedTextView row = (CheckedTextView) super.getView(position, convertView, parent);
				row.setTextColor(colors.ink);
				row.setCheckMarkTintList(new ColorStateList(new int[][] {
						{android.R.attr.state_checked}, {}}, new int[] {colors.primary, colors.outline}));
				row.setMinHeight(Math.round(48 * ResourceUtils.obtainDensity(host)));
				return row;
			}
		};
		TextView title = new TextView(themed);
		title.setText(R.string.player_buttons_per_row); title.setTextColor(colors.ink); title.setTextSize(22);
		int padding = Math.round(24 * ResourceUtils.obtainDensity(host));
		title.setPadding(padding, padding, padding, padding / 2);
		columnsDialog = new MaterialAlertDialogBuilder(themed).setCustomTitle(title)
				.setBackground(PlayerButtonsColors.shape(colors.card, padding))
				.setSingleChoiceItems(adapter, Preferences.getVideoButtonsLayout().columns - 1, (dialog, which) -> {
					if (editor != null) editor.applyCommand(Preferences.getVideoButtonsLayout().withColumns(which + 1), null);
					dialog.dismiss();
				}).setNegativeButton(android.R.string.cancel, null).create();
		// Material's AppCompat dialog is not passed to the native AlertDialog appearance helper.
		SurfaceMotion.configureDialog(columnsDialog);
		columnsDialog.setOnShowListener(dialog -> columnsDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
				.setTextColor(PlayerButtonsColors.contrast(colors.primary, colors.card, 4.5)));
		columnsDialog.show();
	}
	static int title(Button button) {
		switch (button) {
			case SPEED: return R.string.playback_speed;
			case PIP: return R.string.enter_picture_in_picture;
			case TIKTOK: return R.string.video_tiktok_mode;
			case SAVE: return R.string.save;
			case MUTE: return R.string.mute_video;
			case FULLSCREEN: return R.string.player_buttons_fullscreen;
			default: throw new IllegalArgumentException();
		}
	}
	private final class EditorPreference extends Preference.Runtime<Void> {
		EditorPreference() { super(requireContext(), "playerButtonsPreview", null, null, null); }
		@Override public ViewType getViewType() { return ViewType.PLAYER_BUTTONS_PREVIEW; }
		@Override public ViewHolder createViewHolder(ViewGroup parent) {
			editor = new PlayerButtonsEditorView(parent.getContext(), () -> (ViewGroup) getView(),
					PlayerButtonsFragment.this::chooseColumns, PlayerButtonsFragment.this::showHelp);
			editor.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
			return new ViewHolder(editor, null, null, null);
		}
		@Override public void bindViewHolder(ViewHolder holder) {
			// No adapter refresh after drag: the same six Views survive every layout change.
			((PlayerButtonsEditorView) holder.view).reload();
		}
	}
}
