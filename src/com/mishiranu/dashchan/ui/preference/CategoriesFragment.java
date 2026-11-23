package com.mishiranu.dashchan.ui.preference;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.app.AlertDialog;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.CustomSearchView;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.MenuExpandListener;
import java.util.List;

public class CategoriesFragment extends PreferenceFragment {
	private static final String EXTRA_SEARCH_QUERY = "searchQuery";

	private List<SettingsSearchIndex.Entry> searchIndex;
	private SettingsSearchHistory searchHistory;
	private CustomSearchView searchView;
	private MenuItem searchMenuItem;
	private String searchQuery;
	// Input is a one-shot user action, never part of restored navigation state.
	private boolean requestSearchInput;
	private boolean restoringSearch;
	private SearchNavigation searchNavigation;
	private IndexToolbarOutline indexOutline;

	/** Clear only the cast shadow, not the shape, Z order or themed background of the shared bar. */
	private static final class IndexToolbarOutline extends ViewOutlineProvider {
		private final View bar;
		private final ViewOutlineProvider original;

		IndexToolbarOutline(View bar) {
			this.bar = bar;
			original = bar.getOutlineProvider();
		}

		@Override public void getOutline(View view, Outline outline) {
			original.getOutline(view, outline);
			outline.setAlpha(0f);
		}

		void setFlat(boolean flat) {
			ViewOutlineProvider current = bar.getOutlineProvider();
			if (flat && current == original) bar.setOutlineProvider(this);
			else if (!flat && current == this) bar.setOutlineProvider(original);
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		searchQuery = savedInstanceState != null ? savedInstanceState.getString(EXTRA_SEARCH_QUERY) : null;
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
			if (searchQuery != null && !restoringSearch) {
				if (searchNavigation != null) searchNavigation.close();
				searchQuery = query;
				populatePreferences();
			}
		});
		populatePreferences();
	}

	private void populatePreferences() {
		if (indexOutline != null) indexOutline.setFlat(searchQuery == null);
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
						requestSearchInput = false;
						openSearchResult(() -> {
							((FragmentHandler) requireActivity()).pushFragment(entry.createFragment());
							if (history != null) history.record(completedQuery);
						});
					});
				}
			}
			return;
		}

		addSettingsIndexCategory(R.string.general, R.drawable.ic_map, R.drawable.ic_settings_home_general)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new GeneralFragment()));
		if (BuildConfig.ENABLE_APP_SECURITY_SETTINGS) {
			addSettingsIndexCategory(R.string.security_title, R.drawable.ic_verified,
					R.drawable.ic_settings_home_security)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new SecurityFragment()));
		}
		addSettingsIndexCategory(R.string.forums, R.drawable.ic_public, R.drawable.ic_settings_home_forums)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ChansFragment()));
		addSettingsIndexCategory(R.string.experimental_features, R.drawable.ic_verified,
				R.drawable.ic_settings_home_experiments)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ExperimentalFragment()));
		addSettingsIndexCategory(R.string.user_interface, R.drawable.ic_color_lens,
				R.drawable.ic_settings_home_interface)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new InterfaceFragment()));
		addSettingsIndexCategory(R.string.contents, R.drawable.ic_local_library,
				R.drawable.ic_settings_home_contents)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new ContentsFragment()));
		addSettingsIndexCategory(R.string.media, R.drawable.ic_save, R.drawable.ic_settings_home_media)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new MediaFragment()));
		addSettingsIndexCategory(R.string.autohide, R.drawable.ic_custom_fork,
				R.drawable.ic_settings_home_autohide)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AutohideFragment()));
		addSettingsIndexCategory(R.string.custom_application_shortcut, R.drawable.ic_camera_alt,
				R.drawable.ic_settings_home_shortcut)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new CustomShortcutFragment()));
		addSettingsIndexCategory(R.string.accessibility, R.drawable.ic_accessibility,
				R.drawable.ic_settings_home_accessibility)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AccessibilityFragment()));
		addSettingsIndexCategory(R.string.about, R.drawable.ic_info, R.drawable.ic_settings_home_about)
				.setOnClickListener(p -> ((FragmentHandler) requireActivity())
						.pushFragment(new AboutFragment()));
	}

	private void openSearchResult(Runnable navigate) {
		if (searchNavigation != null) return;
		View owner = getView();
		if (owner == null || !isAdded()) return;
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(owner);
		boolean hidingIme = insets != null && (insets.isVisible(WindowInsetsCompat.Type.ime())
				|| insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0);
		if (searchView != null) searchView.clearSearchFocus();
		if (!hidingIme) { navigate.run(); return; }
		// Capture/start the page transition only after IME resize has settled. Otherwise
		// ScreenMotionController correctly treats that resize as invalidated scene geometry.
		searchNavigation = new SearchNavigation(owner, navigate);
		searchNavigation.start();
	}

	private final class SearchNavigation implements ViewTreeObserver.OnPreDrawListener,
			View.OnAttachStateChangeListener {
		final View owner;
		final Runnable navigate;
		final Runnable timeout = this::complete;
		int width = -1, height = -1, stable;
		boolean closed;
		SearchNavigation(View owner, Runnable navigate) { this.owner = owner; this.navigate = navigate; }
		void start() {
			owner.addOnAttachStateChangeListener(this);
			owner.getViewTreeObserver().addOnPreDrawListener(this);
			owner.postDelayed(timeout, 1200); // Safety net for an IME which never reports its final layout.
			owner.postInvalidateOnAnimation();
		}
		@Override public boolean onPreDraw() {
			if (closed) return true;
			if (!owner.hasWindowFocus() || searchView != null && searchView.isSearchFocused()) { close(); return true; }
			WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(owner);
			boolean hidden = insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())
					&& insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0;
			if (hidden && owner.getWidth() == owner.getMeasuredWidth() && owner.getHeight() == owner.getMeasuredHeight()
					&& owner.getWidth() > 0 && owner.getHeight() > 0
					&& width == owner.getWidth() && height == owner.getHeight()) stable++;
			else stable = 0;
			width = owner.getWidth(); height = owner.getHeight();
			if (stable >= 2) complete();
			else owner.postInvalidateOnAnimation();
			return true;
		}
		void complete() {
			if (closed) return;
			close();
			if (isAdded() && isResumed() && getView() == owner && owner.hasWindowFocus()
					&& !getParentFragmentManager().isStateSaved()) navigate.run();
		}
		void close() {
			if (closed) return;
			closed = true; owner.removeCallbacks(timeout); owner.removeOnAttachStateChangeListener(this);
			if (owner.getViewTreeObserver().isAlive()) owner.getViewTreeObserver().removeOnPreDrawListener(this);
			if (searchNavigation == this) searchNavigation = null;
		}
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { close(); }
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
				MotionDialogBuilder.standard(requireContext(), true).setTitle(query)
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
			MotionDialogBuilder.standard(requireContext(), true).setTitle(R.string.settings_search_clear_history)
					.setMessage(R.string.settings_search_history_clear_confirm)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (dialog, which) -> {
						history.clear();
						if (getView() != null) populatePreferences();
					}).show();
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		if (InterfaceAppearance.isEnabled(requireContext())) {
			View bar = requireActivity().findViewById(R.id.toolbar_layout);
			if (bar != null && bar.getOutlineProvider() != null) {
				indexOutline = new IndexToolbarOutline(bar);
				indexOutline.setFlat(searchQuery == null);
			}
		}
	}

	private void releaseIndexOutline() {
		if (indexOutline != null) {
			indexOutline.setFlat(false);
			indexOutline = null;
		}
	}

	@Override
	public void onTerminate() {
		// MainActivity transfers its toolbar before the outgoing view can be destroyed.
		releaseIndexOutline();
		super.onTerminate();
	}

	@Override
	public void onStop() {
		releaseIndexOutline();
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		if (searchNavigation != null) searchNavigation.close();
		super.onDestroyView();

		searchIndex = null;
		searchHistory = null;
		searchView = null;
		searchMenuItem = null;
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		outState.putString(EXTRA_SEARCH_QUERY, searchQuery);
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.preferences), null);
	}

	@Override
	protected boolean shouldInvalidateOptionsMenuOnResume() {
		// STARTED already creates this static menu; retain it when its items still belong to us.
		return !hasCurrentPrimaryOptionsMenu();
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
		if (searchNavigation != null) searchNavigation.close();
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
		if (searchNavigation != null) searchNavigation.close();
		if (searchMenuItem != null) {
			if (searchMenuItem.isActionViewExpanded()) {
				searchView.requestSearchFocus();
			} else {
				requestSearchInput = true;
				searchMenuItem.expandActionView();
			}
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
					searchView.setFocusOnExpand(requestSearchInput);
					requestSearchInput = false;
					if (searchQuery == null) {
						searchQuery = "";
					}
					searchView.setQuery(searchQuery);
					if (!restoringSearch) populatePreferences();
				} else {
					((FragmentHandler) requireActivity()).animateContentBack(this, () -> {
						searchQuery = null;
						requestSearchInput = false;
						populatePreferences();
					});
				}
				requireView().post(this::notifyBackNavigationChanged);
				return true;
			}, () -> !restoringSearch && searchQuery == null));
			if (searchQuery != null) {
				// Restore an already-open search as part of the page transition, without replaying
				// a separate toolbar reveal or rebuilding the results on a synthetic query change.
				restoringSearch = true;
				try {
					searchMenuItem.expandActionView();
				} finally {
					restoringSearch = false;
				}
			}
		}
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_search) {
			return onSearchRequested();
		}
		return super.onMenuItemSelected(item);
	}
}
