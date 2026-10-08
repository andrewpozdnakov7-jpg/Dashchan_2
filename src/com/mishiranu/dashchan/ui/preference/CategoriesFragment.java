package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.CustomSearchView;
import com.mishiranu.dashchan.widget.MenuExpandListener;
import java.util.List;

public class CategoriesFragment extends PreferenceFragment {
	private static final String EXTRA_SEARCH_QUERY = "searchQuery";
	private static final String EXTRA_SEARCH_FOCUSED = "searchFocused";

	private List<SettingsSearchIndex.Entry> searchIndex;
	private SettingsSearchHistory searchHistory;
	private CustomSearchView searchView;
	private MenuItem searchMenuItem;
	private String searchQuery;
	private boolean searchFocused;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		searchQuery = savedInstanceState != null ? savedInstanceState.getString(EXTRA_SEARCH_QUERY) : null;
		searchFocused = savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_SEARCH_FOCUSED);
	}

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		searchIndex = SettingsSearchIndex.create(requireContext());
		searchHistory = SettingsSearchHistory.create(requireContext());
		searchView = obtainSearchView();
		searchView.setHint(getString(R.string.search_settings));
		searchView.setOnChangeListener(query -> {
			if (searchQuery != null) {
				searchQuery = query;
				populatePreferences();
			}
		});
		populatePreferences();
	}

	private void populatePreferences() {
		removeAllPreferences();
		if (searchQuery != null) {
			List<SettingsSearchIndex.Entry> results = SettingsSearchIndex.search(searchIndex, searchQuery);
			if (searchQuery.trim().isEmpty()) {
				populateSearchHistory();
			} else if (results.isEmpty()) {
				addButton(R.string.no_settings_found, 0).setSelectable(false);
			} else {
				String completedQuery = searchQuery;
				for (SettingsSearchIndex.Entry entry : results) {
					addButton(entry.getTitle(), entry.getBreadcrumb()).setOnClickListener(preference -> {
						SettingsSearchHistory history = searchHistory;
						((FragmentHandler) requireActivity()).pushFragment(entry.createFragment());
						if (history != null) history.record(completedQuery);
					});
				}
			}
			return;
		}

		addCategory(R.string.general, R.drawable.ic_map)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new GeneralFragment()));
		addCategory(R.string.forums, R.drawable.ic_public)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ChansFragment()));
		addCategory(R.string.experimental_features, R.drawable.ic_verified)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ExperimentalFragment()));
		addCategory(R.string.user_interface, R.drawable.ic_color_lens)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new InterfaceFragment()));
		addCategory(R.string.contents, R.drawable.ic_local_library)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ContentsFragment()));
		addCategory(R.string.media, R.drawable.ic_save)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new MediaFragment()));
		addCategory(R.string.autohide, R.drawable.ic_custom_fork)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AutohideFragment()));
		addCategory(R.string.about, R.drawable.ic_info)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AboutFragment()));
		addCategory(R.string.custom_application_shortcut, R.drawable.ic_camera_alt)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new CustomShortcutFragment()));
		addCategory(R.string.accessibility, R.drawable.ic_accessibility)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AccessibilityFragment()));
	}

	private void populateSearchHistory() {
		List<String> queries = searchHistory != null ? searchHistory.getQueries() : java.util.Collections.emptyList();
		if (queries.isEmpty()) {
			addButton(R.string.search_settings_hint, 0).setSelectable(false);
			return;
		}
		addHeader(R.string.settings_search_history);
		for (String query : queries) {
			Preference<Void> item =
					addButton(query, getString(R.string.settings_search_history_item_hint));
			item.setOnClickListener(preference -> {
				if (searchView == null) return;
				if (query.equals(searchView.getQuery())) {
					searchQuery = query;
					populatePreferences();
				} else {
					searchView.setQuery(query);
				}
			});
			item.setOnLongClickListener(preference -> {
				SettingsSearchHistory history = searchHistory;
				if (history == null) return;
				new AlertDialog.Builder(requireContext()).setTitle(query)
						.setMessage(R.string.settings_search_history_delete_confirm)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(android.R.string.ok, (dialog, which) -> {
							history.remove(query);
							if (getView() != null) populatePreferences();
						}).show();
			});
		}
		addButton(R.string.settings_search_clear_history, 0).setOnClickListener(preference -> {
			SettingsSearchHistory history = searchHistory;
			if (history == null) return;
			new AlertDialog.Builder(requireContext()).setTitle(R.string.settings_search_clear_history)
					.setMessage(R.string.settings_search_history_clear_confirm)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (dialog, which) -> {
						history.clear();
						if (getView() != null) populatePreferences();
					}).show();
		});
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		searchIndex = null;
		searchHistory = null;
		searchView = null;
		searchMenuItem = null;
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		if (searchView != null) {
			searchFocused = searchView.isSearchFocused();
		}
		outState.putString(EXTRA_SEARCH_QUERY, searchQuery);
		outState.putBoolean(EXTRA_SEARCH_FOCUSED, searchFocused);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.preferences), null);
	}

	@Override
	public void onResume() {
		super.onResume();

		searchIndex = SettingsSearchIndex.create(requireContext());
		if (searchQuery != null) {
			populatePreferences();
		}
	}

	@Override
	public boolean onBackPressed() {
		if (searchMenuItem != null && searchMenuItem.isActionViewExpanded()) {
			searchMenuItem.collapseActionView();
			return true;
		}
		return false;
	}

	@Override
	public boolean canHandleBack() {
		return searchMenuItem != null && searchMenuItem.isActionViewExpanded();
	}

	@Override
	public boolean isSearchMode() {
		return searchQuery != null;
	}

	@Override
	public boolean onSearchRequested() {
		if (searchMenuItem != null) {
			searchFocused = true;
			searchMenuItem.expandActionView();
			return true;
		}
		return false;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		MenuItem searchMenuItem = menu.add(0, R.id.menu_search, 0, R.string.search_settings)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionSearch))
				.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS
						| MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
		if (primary) {
			this.searchMenuItem = searchMenuItem;
			searchMenuItem.setActionView(searchView);
			searchMenuItem.setOnActionExpandListener(new MenuExpandListener((menuItem, expand) -> {
				if (expand) {
					searchView.setFocusOnExpand(searchFocused);
					if (searchQuery == null) {
						searchQuery = "";
					}
					searchView.setQuery(searchQuery);
				} else {
					searchQuery = null;
					searchFocused = false;
				}
				populatePreferences();
				requireView().post(this::notifyBackNavigationChanged);
				return true;
			}));
			if (searchQuery != null) {
				searchMenuItem.expandActionView();
			}
		}
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_search) {
			if (item == searchMenuItem) {
				searchFocused = true;
				return false;
			} else if (searchMenuItem != null) {
				searchFocused = true;
				searchMenuItem.expandActionView();
			}
			return true;
		}
		return super.onMenuItemSelected(item);
	}
}
