package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toolbar;
import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.CustomSearchView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

public abstract class ContentFragment extends Fragment implements MenuProvider {
	private static class MenuState {
		public boolean created;
		public final ArrayList<WeakReference<MenuItem>> actionItems = new ArrayList<>();
	}

	private final WeakHashMap<Menu, MenuState> menuStates = new WeakHashMap<>();
	private boolean menuTerminated;
	private boolean dispatchingMenu;

	public boolean isSearchMode() {
		return false;
	}

	public boolean onSearchRequested() {
		return false;
	}

	public boolean onHomePressed() {
		return onBackPressed();
	}

	public boolean onBackPressed() {
		return false;
	}

	public boolean canHandleBack() {
		return false;
	}

	/**
	 * Returns whether this fragment represents the user's current forum destination rather than a temporary
	 * utility or settings screen. Drawer utilities preserve such destinations so Back returns to the same forum.
	 */
	public boolean isPrimaryNavigationContent() {
		return false;
	}

	protected final void notifyBackNavigationChanged() {
		FragmentActivity activity = getActivity();
		if (activity instanceof StateActivity) {
			((StateActivity) activity).updateSystemBackCallback();
		}
	}

	private void clearOptionMenus() {
		for (WeakHashMap.Entry<Menu, MenuState> entry : menuStates.entrySet()) {
			Menu menu = entry.getKey();
			for (WeakReference<MenuItem> reference : entry.getValue().actionItems) {
				MenuItem menuItem = reference.get();
				if (menuItem != null) {
					menuItem.setOnActionExpandListener(null);
					// A shared toolbar can already belong to another screen. Clean up only our item.
					if (menu.findItem(menuItem.getItemId()) == menuItem && menuItem.isActionViewExpanded()) {
						menuItem.collapseActionView();
					}
				}
			}
		}
		menuStates.clear();
	}

	public void onTerminate() {
		menuTerminated = true;
		if (isAdded()) {
			requireActivity().removeMenuProvider(this);
		}
		clearOptionMenus();
	}

	@Override
	public void onDestroyView() {
		menuTerminated = true;
		super.onDestroyView();

		clearOptionMenus();
		ViewHolderFragment viewHolder = getViewHolder();
		if (viewHolder != null) {
			viewHolder.resetSearchView(this);
		}
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
			// Block hardware menu button if menu is empty. This fixes menu issues on some Android 5 devices
			// and ensures empty hardware menu will never appear.
			boolean hasMenuItems = false;
			for (WeakHashMap.Entry<Menu, MenuState> entry : menuStates.entrySet()) {
				hasMenuItems = entry.getValue().created && entry.getKey().hasVisibleItems();
			}
			if (!hasMenuItems) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
		if (transit == FragmentTransaction.TRANSIT_FRAGMENT_OPEN) {
			return createAnimator(getView(), enter);
		} else {
			return null;
		}
	}

	protected Animator createAnimator(View view, boolean enter) {
		if (enter) {
			ObjectAnimator alphaAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
			alphaAnimator.setDuration(150);
			ObjectAnimator scaleXAnimator = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.925f, 1f);
			scaleXAnimator.setDuration(150);
			ObjectAnimator scaleYAnimator = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.925f, 1f);
			scaleYAnimator.setDuration(150);
			AnimatorSet set = new AnimatorSet();
			set.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator);
			return set;
		} else {
			ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f);
			animator.setDuration(100);
			animator.setInterpolator(new DecelerateInterpolator());
			return animator;
		}
	}

	public static void prepare(FragmentActivity activity) {
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/ContentFragment/prepare")) {
			FragmentManager fragmentManager = activity.getSupportFragmentManager();
			ViewHolderFragment viewHolder = (ViewHolderFragment) fragmentManager.findFragmentByTag(ViewHolderFragment.TAG);
			if (viewHolder == null) {
				viewHolder = new ViewHolderFragment();
				try (AuditDiagnostics.Scope commit = AuditDiagnostics.begin("Audit/ContentFragment/commitNow")
						.count(fragmentManager.isStateSaved() ? 1 : 0)) {
					fragmentManager.beginTransaction().add(viewHolder, ViewHolderFragment.TAG).commitNow();
					commit.result("ok");
				}
				scope.result("created");
			} else {
				scope.result("reused");
			}
		}
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		menuTerminated = false;
		// Activate only after view initialization; unregister automatically when its view is destroyed.
		requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.STARTED);
	}

	@Override
	public void onResume() {
		super.onResume();
		// Request a fresh host-owned menu after resume, never prepare a cached Menu directly.
		// The host may have cleared that same object while this provider was stopped.
		invalidateOptionsMenu();
	}

	private MenuState obtainMenuState(Menu menu) {
		MenuState menuState = menuStates.get(menu);
		if (menuState == null) {
			menuState = new MenuState();
			menuStates.put(menu, menuState);
		}
		return menuState;
	}

	@Override
	public final void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
		if (dispatchingMenu) {
			return;
		}
		MenuState menuState = obtainMenuState(menu);
		// A new host creation invalidates the old contents, even if this screen is not ready yet.
		menuState.created = false;
		menuState.actionItems.clear();
		if (canUseOptionsMenu()) {
			dispatchingMenu = true;
			try {
				onCreateOptionsMenu(menu, isPrimaryMenu(menu));
				for (int i = 0; i < menu.size(); i++) {
					MenuItem item = menu.getItem(i);
					if (item.getActionView() != null) {
						menuState.actionItems.add(new WeakReference<>(item));
					}
				}
				menuState.created = true;
			} finally {
				dispatchingMenu = false;
			}
		}
	}

	@Override
	public final void onPrepareMenu(@NonNull Menu menu) {
		if (dispatchingMenu || !canUseOptionsMenu()) {
			return;
		}
		MenuState menuState = menuStates.get(menu);
		if (menuState == null || !menuState.created) {
			// If creation was skipped, ask the host to run its complete create/prepare cycle.
			// Do not clear or populate a shared toolbar menu from a preparation callback.
			invalidateOptionsMenu();
			return;
		}
		dispatchingMenu = true;
		try {
			onPrepareOptionsMenu(menu, isPrimaryMenu(menu));
		} finally {
			dispatchingMenu = false;
		}
	}

	private boolean canUseOptionsMenu() {
		return !menuTerminated && isAdded() && !isHidden() && getView() != null
				&& getViewLifecycleOwner().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)
				&& isValidOptionsMenuState();
	}

	@Override
	public void onStop() {
		// Keep action views for cleanup/search-state restoration, but forget readiness.
		// MenuHost removes the provider on stop and can clear the menu without notifying us.
		for (MenuState state : menuStates.values()) {
			state.created = false;
		}
		super.onStop();
	}

	public boolean isValidOptionsMenuState() {
		return true;
	}

	public void onCreateOptionsMenu(Menu menu, boolean primary) {}

	public void onPrepareOptionsMenu(Menu menu, boolean primary) {}

	@Override
	public boolean onMenuItemSelected(@NonNull MenuItem item) {
		return false;
	}

	public void invalidateOptionsMenu() {
		// Restoring an expanded search view may request an update from onCreateMenu.
		// The host will prepare that menu immediately afterwards; rebuilding again would loop.
		if (!dispatchingMenu && canUseOptionsMenu()) {
			requireActivity().invalidateMenu();
		}
	}

	private boolean isPrimaryMenu(Menu menu) {
		Toolbar toolbar = (Toolbar) ((FragmentHandler) requireActivity()).getToolbarView();
		return toolbar.getMenu() == menu;
	}

	private ViewHolderFragment getViewHolder() {
		FragmentManager fragmentManager = getParentFragmentManager();
		return (ViewHolderFragment) fragmentManager.findFragmentByTag(ViewHolderFragment.TAG);
	}

	protected CustomSearchView obtainSearchView() {
		ViewHolderFragment viewHolder = getViewHolder();
		return viewHolder.obtainSearchView(this);
	}

	public static class ViewHolderFragment extends Fragment {
		public static final String TAG = ViewHolderFragment.class.getName();

		private CustomSearchView searchView;
		private WeakReference<ContentFragment> searchViewOwner;

		private void resetSearchView(ContentFragment fragment) {
			if (searchView != null) {
				boolean reset;
				if (fragment != null && searchViewOwner != null) {
					ContentFragment ownerFragment = searchViewOwner.get();
					reset = ownerFragment == null || ownerFragment == fragment;
				} else {
					reset = true;
				}
				if (reset) {
					searchView.setOnSubmitListener(null);
					searchView.setOnChangeListener(null);
				}
			}
		}

		private CustomSearchView obtainSearchView(ContentFragment fragment) {
			resetSearchView(null);
			if (searchView == null) {
				searchView = new CustomSearchView(new ContextThemeWrapper(requireContext(),
						R.style.Theme_Special_White));
			}
			searchViewOwner = new WeakReference<>(fragment);
			ViewUtils.removeFromParent(searchView);
			searchView.setQuery("");
			return searchView;
		}
	}
}
