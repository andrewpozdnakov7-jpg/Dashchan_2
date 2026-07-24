package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.LauncherIconManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InterfaceFragment extends PreferenceFragment {
	private Preference<Void> applicationLogoPreference;

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addHeader(R.string.application_shortcut);
		addList(Preferences.KEY_APPLICATION_NAME, LauncherIconManager.getApplicationNames(),
				Preferences.DEFAULT_APPLICATION_NAME, R.string.application_name,
				Arrays.asList("Sloop", "Dashchan_2", "Двач", "Slooop", "Slopchan", "Slopchan_1", "Slopchan_2"))
				.setOnAfterChangeListener(p -> LauncherIconManager.apply(requireContext(), p.getValue()));
		if (LauncherIconManager.arePresetLogosReady()) {
			Preference<Void> logoPreference = addButton(getString(R.string.application_logo), preference ->
					getString(LauncherIconManager.getLogoOption(Preferences.getApplicationLogo()).titleResId));
			applicationLogoPreference = logoPreference;
			logoPreference.setOnClickListener(preference ->
					ApplicationLogoDialog.show(getChildFragmentManager()));
		}
		addHeader(R.string.appearance);
		String scaleFormat = ResourceUtils.getColonString(getResources(), R.string.scale, "%d%%");
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
		List<String> lightThemeValues = new ArrayList<>();
		List<CharSequence> lightThemeEntries = new ArrayList<>();
		List<String> darkThemeValues = new ArrayList<>();
		List<CharSequence> darkThemeEntries = new ArrayList<>();
		for (ThemeEngine.Theme theme : ThemeEngine.getThemes()) {
			List<String> values = theme.base == ThemeEngine.Theme.Base.DARK ? darkThemeValues : lightThemeValues;
			List<CharSequence> entries = theme.base == ThemeEngine.Theme.Base.DARK
					? darkThemeEntries : lightThemeEntries;
			values.add(theme.name);
			entries.add(theme.name);
		}
		addCheck(true, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME,
				Preferences.DEFAULT_AUTOMATIC_DAY_NIGHT_THEME, R.string.automatic_day_night_themes,
				R.string.automatic_day_night_themes__summary)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addList(Preferences.KEY_DAY_THEME, lightThemeValues, lightThemeValues.get(0),
				R.string.day_theme, lightThemeEntries).setOnAfterChangeListener(p -> requireActivity().recreate());
		addList(Preferences.KEY_NIGHT_THEME, darkThemeValues, darkThemeValues.get(0),
				R.string.night_theme, darkThemeEntries).setOnAfterChangeListener(p -> requireActivity().recreate());
		addDependency(Preferences.KEY_DAY_THEME, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME, true);
		addDependency(Preferences.KEY_NIGHT_THEME, Preferences.KEY_AUTOMATIC_DAY_NIGHT_THEME, true);
		addCheck(true, Preferences.KEY_PREDICTIVE_BACK, Preferences.DEFAULT_PREDICTIVE_BACK,
				R.string.enable_predictive_back, R.string.enable_predictive_back__summary)
				.setOnAfterChangeListener(p -> ((StateActivity) requireActivity()).updateSystemBackCallback());
		addButton(R.string.gesture_controls, R.string.gesture_controls__summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new GestureSettingsFragment()));

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
		addCheck(true, Preferences.KEY_REMOVE_HIDDEN_POSTS, Preferences.DEFAULT_REMOVE_HIDDEN_POSTS,
				R.string.remove_hidden_posts, R.string.remove_hidden_posts__summary);
		addCheck(true, Preferences.KEY_DISPLAY_POST_YEAR, Preferences.DEFAULT_DISPLAY_POST_YEAR,
				R.string.display_post_year, 0).setOnAfterChangeListener(p -> requireActivity().recreate());
		addCheck(true, Preferences.KEY_HIDE_THREAD_TITLE, Preferences.DEFAULT_HIDE_THREAD_TITLE,
				R.string.hide_thread_title, R.string.hide_thread_title__summary);
		addEdit(Preferences.KEY_POST_MAX_LINES, Preferences.DEFAULT_POST_MAX_LINES,
				R.string.max_lines_count, R.string.max_lines_count__summary, null, InputType.TYPE_CLASS_NUMBER);
		addCheck(true, Preferences.KEY_ALL_ATTACHMENTS, Preferences.DEFAULT_ALL_ATTACHMENTS,
				R.string.all_attachments, R.string.all_attachments__summary);
		addList(Preferences.KEY_HIGHLIGHT_UNREAD, enumList(Preferences.HighlightUnreadMode.values(), o -> o.value),
				Preferences.DEFAULT_HIGHLIGHT_UNREAD.value, R.string.highlight_unread_posts,
				enumResList(Preferences.HighlightUnreadMode.values(), o -> o.titleResId));
		addCheck(true, Preferences.KEY_SHOW_MY_POSTS, Preferences.DEFAULT_SHOW_MY_POSTS,
				R.string.highlight_my_posts, 0);
		addButton(getString(R.string.post_marks_colors), preference -> getString(
				Preferences.getPostMarksColorMode().titleResId)).setOnClickListener(preference ->
				((FragmentHandler) requireActivity()).pushFragment(new PostMarksColorsFragment()));
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

	void onApplicationLogoChanged() {
		if (applicationLogoPreference != null) {
			applicationLogoPreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		applicationLogoPreference = null;
		super.onDestroyView();
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

	private static final ChanMarkup.MarkupBuilder BUILDER_ADVANCED_SEARCH = new ChanMarkup
			.MarkupBuilder(markup -> markup.addTag("b", ChanMarkup.TAG_BOLD));
}
