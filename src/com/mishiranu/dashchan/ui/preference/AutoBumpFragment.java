package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.service.AutoBumpWorker;
import com.mishiranu.dashchan.content.storage.AutoBumpStorage;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.util.ArrayList;
import java.util.List;

public class AutoBumpFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	public void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		addCheck(true, Preferences.KEY_AUTO_BUMP_ENABLED, Preferences.DEFAULT_AUTO_BUMP_ENABLED,
				R.string.auto_bump, R.string.auto_bump__summary)
				.setOnAfterChangeListener(p -> {
					AutoBumpWorker.scheduleNext(requireContext());
					refreshPreferences();
				});
		addButton(R.string.auto_bump_how_it_works, R.string.auto_bump_how_it_works__summary)
				.setOnClickListener(p -> new AlertDialog.Builder(requireContext())
						.setTitle(R.string.auto_bump_how_it_works)
						.setMessage(R.string.auto_bump_instructions)
						.setPositiveButton(android.R.string.ok, null)
						.show());
		boolean hasPass = Preferences.checkHasMultipleValues(Preferences.getCaptchaPass(Chan.get("dvach")));
		Preference<Void> addTask = addButton(R.string.add_auto_bump_task,
				hasPass ? 0 : R.string.auto_bump_pass_required);
		addTask.setEnabled(hasPass);
		addTask.setOnClickListener(p -> showTaskDialog(null, null, null));
		Preference<Void> favorites = addButton(R.string.add_from_favorites, 0);
		favorites.setEnabled(hasPass);
		favorites.setOnClickListener(p -> showFavorites());

		List<AutoBumpStorage.Task> tasks = AutoBumpStorage.getInstance().getTasks();
		if (tasks.isEmpty()) {
			addHeader(R.string.auto_bump_no_tasks);
		} else {
			addHeader(R.string.manage_auto_bump);
			for (AutoBumpStorage.Task task : tasks) {
				String status;
				if (task.completed) {
					status = getString(R.string.auto_bump_status_completed);
				} else if (!task.enabled) {
					status = getString(R.string.auto_bump_status_paused);
				} else if (!Preferences.isAutoBumpEnabled()) {
					status = getString(R.string.auto_bump_status_disabled);
				} else if (!hasPass) {
					status = getString(R.string.auto_bump_status_pass_required);
				} else {
					status = getString(R.string.auto_bump_status_active);
				}
				String summary = getString(R.string.auto_bump_task__format, status,
						task.intervalMinutes, task.nextNumber);
				if (task.enabled && Preferences.isAutoBumpEnabled() && hasPass) {
					long remainingMinutes = AutoBumpStorage.calculateRemainingMinutes(task.nextRunAt,
							System.currentTimeMillis());
					summary += "\n" + (remainingMinutes > 0L
							? getString(R.string.auto_bump_next_check__format, remainingMinutes)
							: getString(R.string.auto_bump_next_check_due));
				}
				if (task.consecutiveFailures > 0) {
					summary += "\n" + getString(R.string.auto_bump_failures__format,
							task.consecutiveFailures, StringUtils.emptyIfNull(task.lastError));
				}
				String title = "/" + task.boardName + "/res/" + task.threadNumber;
				addButton(title, summary).setOnClickListener(p -> AutoBumpDialog.editTask(task.id)
						.show(getChildFragmentManager(), AutoBumpDialog.class.getName()));
			}
		}
	}

	private void showTaskDialog(String boardName, String threadNumber, String title) {
		if (AutoBumpStorage.getInstance().getTasks().size() >= AutoBumpStorage.MAX_TASKS) {
			ClickableToast.show(R.string.auto_bump_task_limit);
			return;
		}
		AutoBumpDialog.newTask(boardName, threadNumber, title)
				.show(getChildFragmentManager(), AutoBumpDialog.class.getName());
	}

	private void showFavorites() {
		ArrayList<FavoritesStorage.FavoriteItem> source = FavoritesStorage.getInstance().getThreads("dvach");
		ArrayList<FavoritesStorage.FavoriteItem> favorites = new ArrayList<>();
		ArrayList<String> titles = new ArrayList<>();
		for (FavoritesStorage.FavoriteItem favorite : source) {
			if (favorite.threadNumber != null) {
				favorites.add(favorite);
				String title = "/" + favorite.boardName + "/res/" + favorite.threadNumber;
				if (!StringUtils.isEmpty(favorite.title)) {
					title += " - " + favorite.title;
				}
				titles.add(title);
			}
		}
		if (favorites.isEmpty()) {
			ClickableToast.show(R.string.auto_bump_no_favorites);
			return;
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.add_from_favorites)
				.setItems(titles.toArray(new String[0]), (dialog, which) -> {
					FavoritesStorage.FavoriteItem favorite = favorites.get(which);
					showTaskDialog(favorite.boardName, favorite.threadNumber, favorite.title);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.manage_auto_bump), null);
	}
}
