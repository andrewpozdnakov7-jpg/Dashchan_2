package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.SafePasteEditText;
import java.util.Arrays;

public class InterfaceFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addHeader(R.string.application_shortcut);
		addList(Preferences.KEY_APPLICATION_NAME, Arrays.asList(LauncherIconManager.VALUE_DASHCHAN_2,
				LauncherIconManager.VALUE_SLOPCHAN, LauncherIconManager.VALUE_DVACH),
				Preferences.DEFAULT_APPLICATION_NAME, R.string.application_name,
				Arrays.asList("Dashchan_2", "Slopchan", "ДВАЧ"))
				.setOnAfterChangeListener(p -> LauncherIconManager.apply(requireContext(), p.getValue()));
		addButton(R.string.custom_application_shortcut, R.string.custom_application_shortcut__summary)
				.setOnClickListener(p -> displayCustomShortcutDialog(getChildFragmentManager()));

		addHeader(R.string.appearance);
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%");
		addSeek(Preferences.KEY_TEXT_SCALE, Preferences.DEFAULT_TEXT_SCALE,
				getString(R.string.text_scale), scaleFormat, null,
				Preferences.MIN_TEXT_SCALE, Preferences.MAX_TEXT_SCALE, Preferences.STEP_TEXT_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addSeek(Preferences.KEY_THUMBNAILS_SCALE, Preferences.DEFAULT_THUMBNAILS_SCALE,
				getString(R.string.thumbnail_scale), scaleFormat, null,
				Preferences.MIN_THUMBNAILS_SCALE, Preferences.MAX_THUMBNAILS_SCALE, Preferences.STEP_THUMBNAILS_SCALE)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addCheck(true, Preferences.KEY_CUT_THUMBNAILS, Preferences.DEFAULT_CUT_THUMBNAILS,
				R.string.crop_thumbnails, R.string.crop_thumbnails__summary);
		addCheck(true, Preferences.KEY_ACTIVE_SCROLLBAR, Preferences.DEFAULT_ACTIVE_SCROLLBAR,
				R.string.active_scrollbar, 0);
		addCheck(true, Preferences.KEY_SCROLL_THREAD_GALLERY, Preferences.DEFAULT_SCROLL_THREAD_GALLERY,
				R.string.scroll_thread_when_scrolling_gallery, 0);
		addButton(R.string.themes, 0).setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(new ThemesFragment()));

		addHeader(R.string.navigation_drawer);
		addList(Preferences.KEY_PAGES_LIST, enumList(Preferences.PagesListMode.values(), o -> o.value),
				Preferences.DEFAULT_PAGES_LIST.value, R.string.headers_order,
				enumResList(Preferences.PagesListMode.values(), o -> o.titleResId));
		addList(Preferences.KEY_DRAWER_INITIAL_POSITION,
				enumList(Preferences.DrawerInitialPosition.values(), o -> o.value),
				Preferences.DEFAULT_DRAWER_INITIAL_POSITION.value, R.string.initial_position,
				enumResList(Preferences.DrawerInitialPosition.values(), o -> o.titleResId));

		addHeader(R.string.threads_list);
		addCheck(true, Preferences.KEY_PAGE_BY_PAGE, Preferences.DEFAULT_PAGE_BY_PAGE,
				R.string.paged_board_navigation, R.string.paged_board_navigation__summary);
		addCheck(true, Preferences.KEY_DISPLAY_HIDDEN_THREADS,
				Preferences.DEFAULT_DISPLAY_HIDDEN_THREADS, R.string.display_hidden_threads, 0);
		addCheck(true, Preferences.KEY_HIDE_THREADS_WITH_SWIPE,
				Preferences.DEFAULT_HIDE_THREADS_WITH_SWIPE, R.string.hide_threads_with_swipe, 0);

		addHeader(R.string.posts_list);
		addEdit(Preferences.KEY_POST_MAX_LINES, Preferences.DEFAULT_POST_MAX_LINES,
				R.string.max_lines_count, R.string.max_lines_count__summary, null, InputType.TYPE_CLASS_NUMBER);
		addCheck(true, Preferences.KEY_ALL_ATTACHMENTS, Preferences.DEFAULT_ALL_ATTACHMENTS,
				R.string.all_attachments, R.string.all_attachments__summary);
		addList(Preferences.KEY_HIGHLIGHT_UNREAD, enumList(Preferences.HighlightUnreadMode.values(), o -> o.value),
				Preferences.DEFAULT_HIGHLIGHT_UNREAD.value, R.string.highlight_unread_posts,
				enumResList(Preferences.HighlightUnreadMode.values(), o -> o.titleResId));
		addCheck(true, Preferences.KEY_SHOW_MY_POSTS, Preferences.DEFAULT_SHOW_MY_POSTS,
				R.string.highlight_my_posts, 0);
		addCheck(true, Preferences.KEY_ADVANCED_SEARCH, Preferences.DEFAULT_ADVANCED_SEARCH,
				R.string.advanced_search, R.string.advanced_search__summary)
				.setOnAfterChangeListener(p -> {
					if (p.getValue()) {
						displayAdvancedSearchDialog(getChildFragmentManager());
					}
				});
		addCheck(true, Preferences.KEY_DISPLAY_ICONS, Preferences.DEFAULT_DISPLAY_ICONS,
				R.string.display_post_icons, R.string.display_post_icons__summary);

		addHeader(R.string.submission_form);
		addCheck(true, Preferences.KEY_HIDE_PERSONAL_DATA,
				Preferences.DEFAULT_HIDE_PERSONAL_DATA, R.string.hide_personal_data_block, 0);
		addCheck(true, Preferences.KEY_HUGE_CAPTCHA, Preferences.DEFAULT_HUGE_CAPTCHA,
				R.string.huge_captcha, 0);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.user_interface), null);
	}

	private static void displayAdvancedSearchDialog(FragmentManager fragmentManager) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			String html = IOUtils.readRawResourceString(context.getResources(), R.raw.markup_advanced_search);
			return new AlertDialog.Builder(context)
					.setTitle(R.string.advanced_search)
					.setMessage(BUILDER_ADVANCED_SEARCH.fromHtmlReduced(html))
					.setPositiveButton(android.R.string.ok, null)
					.create();
		});
	}

	private static void displayCustomShortcutDialog(FragmentManager fragmentManager) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			EditText editText = new SafePasteEditText(context);
			editText.setId(android.R.id.edit);
			editText.setSingleLine(true);
			editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
			LinearLayout layout = new LinearLayout(context);
			layout.setOrientation(LinearLayout.HORIZONTAL);
			layout.addView(editText, LinearLayout.LayoutParams.MATCH_PARENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			int padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding_view);
			layout.setPadding(padding, padding, padding, padding);
			AlertDialog dialog = new AlertDialog.Builder(context)
					.setTitle(R.string.custom_application_shortcut)
					.setView(layout)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (d, which) -> {
						String name = editText.getText().toString().trim();
						if (name.isEmpty()) {
							ClickableToast.show(R.string.enter_valid_data);
						} else if (LauncherIconManager.requestCustomShortcut(context, name)) {
							ClickableToast.show(R.string.confirm_custom_shortcut);
						} else {
							ClickableToast.show(R.string.custom_shortcuts_not_supported);
						}
					}).create();
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
			return dialog;
		});
	}

	private static final ChanMarkup.MarkupBuilder BUILDER_ADVANCED_SEARCH = new ChanMarkup
			.MarkupBuilder(markup -> markup.addTag("b", ChanMarkup.TAG_BOLD));
}
