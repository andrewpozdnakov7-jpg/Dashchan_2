package com.mishiranu.dashchan.ui.preference;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.Preferences.ThreadFabAction;
import com.mishiranu.dashchan.content.Preferences.ThreadFabPressAction;
import com.mishiranu.dashchan.content.Preferences.ThreadFabRefreshLongPress;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.DialogPreference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.List;

public class ThreadFabSettingsFragment extends PreferenceFragment {
	// Menu toggle, custom order toggle and category.
	private static final int ACTION_OFFSET = 3;
	private final ArrayList<ThreadFabAction> order = new ArrayList<>();
	private RecyclerView list;
	private ItemTouchHelper dragHelper;
	private AlertDialog orderDialog;

	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		list = view.findViewById(android.R.id.list);
		dragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
			@Override
			public int getMovementFlags(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
				return isActionRow(holder) ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
			}

			@Override
			public boolean canDropOver(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder current,
					@NonNull RecyclerView.ViewHolder target) {
				return isActionRow(target);
			}

			@Override
			public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder from,
					@NonNull RecyclerView.ViewHolder to) {
				if (!isActionRow(from) || !isActionRow(to)) return false;
				moveAction(from.getBindingAdapterPosition() - ACTION_OFFSET,
						to.getBindingAdapterPosition() - ACTION_OFFSET);
				Preferences.setThreadFabActionOrder(order);
				return true;
			}

			@Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {}
			@Override public boolean isLongPressDragEnabled() { return false; }
		});
		dragHelper.attachToRecyclerView(list);

		order.addAll(Preferences.getThreadFabActionOrder());
		addCheck(true, Preferences.KEY_THREAD_FAB_MENU, Preferences.DEFAULT_THREAD_FAB_MENU,
				R.string.thread_fab_menu, 0);
		addCheck(true, Preferences.KEY_THREAD_FAB_CUSTOM_ORDER, Preferences.isThreadFabCustomOrderEnabled(),
				R.string.thread_fab_custom_order, R.string.thread_fab_order_summary)
				.setOnAfterChangeListener(p -> applyCustomOrder());
		addCategory(R.string.thread_fab_actions);
		for (ThreadFabAction action : order) {
			ActionPreference preference = new ActionPreference(action);
			addPreference(preference, true);
			preference.updateOrderAccess(Preferences.isThreadFabCustomOrderEnabled());
		}
		PressPreference shortPress = new PressPreference(false);
		PressPreference longPress = new PressPreference(true);
		addDialogPreference(shortPress);
		addDialogPreference(longPress);
		for (ThreadFabAction action : order) {
			((ActionPreference) findPreference(action.key)).setOnAfterChangeListener(preference -> {
				if (!preference.getValue()) {
					if (shortPress.getValue().action == action) shortPress.setValue(ThreadFabPressAction.AUTO);
					if (longPress.getValue().action == action) longPress.setValue(ThreadFabPressAction.AUTO);
				}
			});
		}
		ThreadFabRefreshLongPress[] options = ThreadFabRefreshLongPress.values();
		addList(Preferences.KEY_THREAD_FAB_REFRESH_LONG_PRESS, enumList(options, option -> option.value),
				Preferences.DEFAULT_THREAD_FAB_REFRESH_LONG_PRESS.value, R.string.thread_fab_refresh_long_press,
				enumResList(options, option -> option.titleResId));
		addDependency(Preferences.KEY_THREAD_FAB_REFRESH_LONG_PRESS, ThreadFabAction.REFRESH.key, true);
	}

	private boolean isActionRow(RecyclerView.ViewHolder holder) {
		int index = holder.getBindingAdapterPosition() - ACTION_OFFSET;
		return Preferences.isThreadFabCustomOrderEnabled() && index >= 0 && index < order.size();
	}

	private void moveAction(int from, int to) {
		if (from == to) return;
		order.add(to, order.remove(from));
		movePreferenceAt(ACTION_OFFSET + from, ACTION_OFFSET + to);
	}

	private void applyCustomOrder() {
		if (orderDialog != null) orderDialog.dismiss();
		boolean custom = Preferences.isThreadFabCustomOrderEnabled();
		List<ThreadFabAction> target = Preferences.getThreadFabActionOrder();
		for (int i = 0; i < target.size(); i++) {
			ThreadFabAction action = target.get(i);
			moveAction(order.indexOf(action), i);
			((ActionPreference) findPreference(action.key)).updateOrderAccess(custom);
		}
	}

	private void choosePosition(ThreadFabAction action) {
		if (!Preferences.isThreadFabCustomOrderEnabled()) return;
		String[] entries = new String[order.size()];
		for (int i = 0; i < entries.length; i++) entries[i] = (i + 1) + ". " + getString(order.get(i).titleResId);
		if (orderDialog != null) orderDialog.dismiss();
		orderDialog = MotionDialogBuilder.standard(requireContext()).setTitle(action.titleResId)
				.setSingleChoiceItems(entries, order.indexOf(action), (dialog, position) -> {
					int from = order.indexOf(action);
					if (getView() != null && Preferences.isThreadFabCustomOrderEnabled() &&
							from >= 0 && from != position) {
						moveAction(from, position);
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
		dragHelper.attachToRecyclerView(null);
		dragHelper = null;
		list = null;
		order.clear();
		super.onDestroyView();
	}

	private static class ActionViewHolder extends CheckPreference.CheckViewHolder {
		final ImageView handle;

		ActionViewHolder(CheckPreference.CheckViewHolder holder, ImageView handle) {
			super(holder, holder.check);
			this.handle = handle;
		}
	}

	private final class PressPreference extends DialogPreference<ThreadFabPressAction> {
		private final boolean longPress;

		PressPreference(boolean longPress) {
			super(requireContext(), longPress ? Preferences.KEY_THREAD_FAB_HOLD : Preferences.KEY_THREAD_FAB_CLICK,
					ThreadFabPressAction.AUTO, getString(longPress ? R.string.thread_fab_hold : R.string.thread_fab_click),
					p -> getString(p.getValue().titleResId));
			this.longPress = longPress;
		}

		@Override protected void extract(SharedPreferences preferences) {
			setValue(Preferences.getThreadFabPressAction(longPress));
		}

		@Override protected void persist(SharedPreferences preferences) {
			preferences.edit().put(key, getValue().value).close();
		}

		@Override
		protected AlertDialog.Builder configureDialog(Bundle state, AlertDialog.Builder builder) {
			List<ThreadFabAction> enabled = Preferences.getEnabledThreadFabActions();
			ArrayList<ThreadFabPressAction> options = new ArrayList<>();
			for (ThreadFabPressAction option : ThreadFabPressAction.values()) {
				if (option == ThreadFabPressAction.NONE && !longPress) continue;
				if (option.action == null || enabled.contains(option.action)) options.add(option);
			}
			CharSequence[] labels = new CharSequence[options.size()];
			for (int i = 0; i < labels.length; i++) labels[i] = getText(options.get(i).titleResId);
			return super.configureDialog(state, builder)
					.setSingleChoiceItems(labels, options.indexOf(getValue()), (dialog, index) -> {
						dialog.dismiss();
						ConcurrentUtils.HANDLER.post(() -> setValue(options.get(index)));
					});
		}
	}

	private final class ActionPreference extends CheckPreference {
		private final ThreadFabAction action;

		ActionPreference(ThreadFabAction action) {
			super(requireContext(), action.key, action.enabledByDefault, getString(action.titleResId), null);
			this.action = action;
			setOnClickListener(p -> p.setValue(!p.getValue()));
		}

		void updateOrderAccess(boolean enabled) {
			setOnLongClickListener(enabled ? p -> choosePosition(action) : null);
			invalidate();
		}

		@Override public ViewType getViewType() { return ViewType.THREAD_FAB_ACTION; }

		@Override
		public ActionViewHolder createViewHolder(ViewGroup parent) {
			CheckViewHolder holder = super.createViewHolder(parent);
			float density = ResourceUtils.obtainDensity(parent);
			int size = (int) (48 * density);
			holder.widgetFrame.removeView(holder.check);
			LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(size, size);
			checkParams.setMarginEnd((int) (8 * density));
			((LinearLayout) holder.view).addView(holder.check, 0, checkParams);
			holder.title.setMaxLines(Integer.MAX_VALUE);
			holder.title.setEllipsize(null);
			int padding = (int) (12 * density);
			ImageView handle = new ImageView(parent.getContext());
			handle.setImageResource(R.drawable.ic_drag_handle);
			handle.setImageTintList(ColorStateList.valueOf(ThemeEngine.getTheme(parent.getContext()).accent));
			handle.setPadding(padding, padding, padding, padding);
			handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			holder.widgetFrame.addView(handle, size, size);
			return new ActionViewHolder(holder, handle);
		}

		@SuppressLint("ClickableViewAccessibility") // The row offers an accessible position picker on long-click.
		@Override
		public void bindViewHolder(ViewHolder holder) {
			super.bindViewHolder(holder);
			ImageView handle = ((ActionViewHolder) holder).handle;
			holder.widgetFrame.setVisibility(Preferences.isThreadFabCustomOrderEnabled() ? View.VISIBLE : View.GONE);
			handle.setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN && list != null) {
					RecyclerView.ViewHolder row = list.findContainingViewHolder(holder.view);
					if (row != null && isActionRow(row)) dragHelper.startDrag(row);
				}
				return true;
			});
		}
	}
}
