package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import androidx.fragment.app.FragmentManager;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;

/** Existing post-list controls, grouped without changing their behavior. */
public class PostsListSettingsFragment extends PreferenceFragment {
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		addCheck(true, Preferences.KEY_THREAD_GALLERY_BUTTON, Preferences.DEFAULT_THREAD_GALLERY_BUTTON,
				R.string.thread_gallery_button, R.string.thread_gallery_button__summary);
		addCheck(true, Preferences.KEY_SHOW_ORIGINAL_POST_TITLE, Preferences.DEFAULT_SHOW_ORIGINAL_POST_TITLE,
				R.string.show_original_post_title, R.string.show_original_post_title__summary)
				.setOnAfterChangeListener(p -> requireActivity().recreate());
		addButton(R.string.thread_quick_navigation, R.string.thread_quick_navigation__search_summary)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ThreadQuickNavigationFragment()));
		addCheck(true, Preferences.KEY_SWIPE_REPLY, Preferences.DEFAULT_SWIPE_REPLY,
				R.string.swipe_reply, R.string.swipe_reply__summary);
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

	}

	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.posts_list), null);
	}

	private static void displayAdvancedSearchDialog(FragmentManager fragmentManager) {
		new InstanceDialog(fragmentManager, null, provider -> {
			Context context = provider.getContext();
			String html = IOUtils.readRawResourceString(context.getResources(), R.raw.markup_advanced_search);
			return MotionDialogBuilder.standard(context)
					.setTitle(R.string.advanced_search)
					.setMessage(BUILDER_ADVANCED_SEARCH.fromHtmlReduced(html))
					.setPositiveButton(android.R.string.ok, null)
					.create();
		});
	}

	private static final ChanMarkup.MarkupBuilder BUILDER_ADVANCED_SEARCH = new ChanMarkup
			.MarkupBuilder(markup -> markup.addTag("b", ChanMarkup.TAG_BOLD));
}
