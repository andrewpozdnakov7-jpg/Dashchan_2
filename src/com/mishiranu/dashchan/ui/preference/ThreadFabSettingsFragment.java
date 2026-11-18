package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.Preferences.ThreadFabAction;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import java.util.List;

public class ThreadFabSettingsFragment extends PreferenceFragment {
	// Toggle, order button and category.
	private static final int ACTION_OFFSET = 3;
	private List<ThreadFabAction> order;
	private AlertDialog orderDialog;

	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		order = Preferences.getThreadFabActionOrder();
		addCheck(true, Preferences.KEY_THREAD_FAB_MENU, Preferences.DEFAULT_THREAD_FAB_MENU,
				R.string.thread_fab_menu, 0);
		addButton(R.string.thread_fab_order, R.string.thread_fab_order_summary)
				.setOnClickListener(p -> chooseAction());
		addCategory(R.string.thread_fab_actions);
		for (ThreadFabAction action : order) {
			addCheck(true, action.key, action.enabledByDefault, action.titleResId, 0)
					.setOnLongClickListener(p -> choosePosition(action));
		}
	}

	private void chooseAction() {
		CharSequence[] titles = new CharSequence[order.size()];
		for (int i = 0; i < titles.length; i++) titles[i] = getString(order.get(i).titleResId);
		orderDialog = MotionDialogBuilder.standard(requireContext()).setTitle(R.string.thread_fab_order)
				.setItems(titles, (dialog, which) -> {
					dialog.dismiss();
					choosePosition(order.get(which));
				}).setNegativeButton(android.R.string.cancel, null).show();
	}

	private void choosePosition(ThreadFabAction action) {
		String[] entries = new String[order.size()];
		for (int i = 0; i < entries.length; i++) entries[i] = (i + 1) + ". " + getString(order.get(i).titleResId);
		orderDialog = MotionDialogBuilder.standard(requireContext()).setTitle(action.titleResId)
				.setSingleChoiceItems(entries, order.indexOf(action), (dialog, position) -> {
					int from = order.indexOf(action);
					if (from != position) {
						order.add(position, order.remove(from));
						movePreferenceAt(ACTION_OFFSET + from, ACTION_OFFSET + position);
						Preferences.setThreadFabActionOrder(order);
					}
					dialog.dismiss();
				}).setNegativeButton(android.R.string.cancel, null).show();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.thread_fab_menu), null);
	}

	@Override
	public void onDestroyView() {
		if (orderDialog != null) {
			orderDialog.dismiss();
			orderDialog = null;
		}
		super.onDestroyView();
	}
}
