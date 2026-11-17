package com.mishiranu.dashchan.ui.preference;

import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.Preferences.DrawerSection;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;

public class DrawerOrderFragment extends PreferenceFragment {
	private final ArrayList<DrawerSection> order = new ArrayList<>();
	private RecyclerView list;
	private ItemTouchHelper dragHelper;
	private AlertDialog positionDialog;
	// The custom-order checkbox is the only row before the sections.
	private static final int SECTION_OFFSET = 1;

	@Override
	protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		list = view.findViewById(android.R.id.list);
		dragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP |
				ItemTouchHelper.DOWN, 0) {
			@Override
			public int getMovementFlags(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
				return isSection(holder) ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
			}

			@Override
			public boolean canDropOver(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder current,
					@NonNull RecyclerView.ViewHolder target) { return isSection(target); }

			@Override
			public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder from,
					@NonNull RecyclerView.ViewHolder to) {
				if (!isSection(from) || !isSection(to)) return false;
				moveSection(from.getBindingAdapterPosition() - SECTION_OFFSET,
						to.getBindingAdapterPosition() - SECTION_OFFSET);
				return true;
			}

			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {}

			@Override public boolean isLongPressDragEnabled() { return false; }
		});
		dragHelper.attachToRecyclerView(list);
		refreshPreferences();
	}

	private boolean isSection(RecyclerView.ViewHolder holder) {
		int position = holder.getBindingAdapterPosition();
		return Preferences.isDrawerCustomOrderEnabled() && position >= SECTION_OFFSET &&
				position < SECTION_OFFSET + order.size() && getPreferenceAt(position) instanceof SectionPreference;
	}

	private void moveSection(int from, int to) {
		if (from == to) return;
		order.add(to, order.remove(from));
		movePreferenceAt(from + SECTION_OFFSET, to + SECTION_OFFSET);
		// Persist each move: rotation/back during a drag must not lose the chosen order.
		Preferences.setDrawerSectionOrder(order);
	}

	private void refreshPreferences() {
		if (positionDialog != null) { positionDialog.dismiss(); positionDialog = null; }
		removeAllPreferences();
		order.clear();
		order.addAll(Preferences.isDrawerCustomOrderEnabled()
				? Preferences.getDrawerSectionOrder() : Preferences.getDefaultDrawerSectionOrder());
		addCheck(true, Preferences.KEY_DRAWER_CUSTOM_ORDER, false, R.string.drawer_custom_order,
				R.string.drawer_section_order_summary).setOnAfterChangeListener(p -> refreshPreferences());
		for (DrawerSection section : order) addPreference(new SectionPreference(section), false);
		addButton(R.string.restore_defaults, R.string.drawer_restore_defaults_summary).setOnClickListener(p -> {
			Preferences.resetDrawerMenu();
			refreshPreferences();
		});
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.drawer_section_order), null);
	}

	@Override
	public void onDestroyView() {
		if (positionDialog != null) {
			positionDialog.dismiss();
			positionDialog = null;
		}
		dragHelper.attachToRecyclerView(null);
		dragHelper = null;
		list = null;
		super.onDestroyView();
	}

	private void choosePosition(DrawerSection section) {
		if (!Preferences.isDrawerCustomOrderEnabled()) return;
		String[] entries = new String[order.size()];
		for (int i = 0; i < order.size(); i++) entries[i] = (i + 1) + ". " + getString(order.get(i).titleResId);
		if (positionDialog != null) positionDialog.dismiss();
		positionDialog = MotionDialogBuilder.standard(requireContext()).setTitle(section.titleResId)
				.setSingleChoiceItems(entries, order.indexOf(section), (dialog, position) -> {
					int from = order.indexOf(section);
					if (getView() != null && Preferences.isDrawerCustomOrderEnabled() && from >= 0) {
						moveSection(from, position);
					}
					dialog.dismiss();
				}).setNegativeButton(android.R.string.cancel, null).show();
	}

	private static class SectionViewHolder extends Preference.ViewHolder {
		final CheckBox visible;
		final ImageView handle;
		SectionViewHolder(Preference.ViewHolder holder, CheckBox visible, ImageView handle) {
			super(holder); this.visible = visible; this.handle = handle;
		}
	}

	private CharSequence sectionSummary(DrawerSection section) {
		return !section.canHide() ? getString(R.string.drawer_settings_always_visible)
				: !Preferences.isDrawerSectionVisible(section) ? getString(R.string.drawer_section_hidden) : null;
	}

	private final class SectionPreference extends Preference<Void> {
		private final DrawerSection section;
		SectionPreference(DrawerSection section) {
			super(requireContext(), "drawer_section_" + section.key, null, getString(section.titleResId),
					p -> DrawerOrderFragment.this.sectionSummary(section));
			this.section = section;
			setOnClickListener(p -> {
				if (Preferences.isDrawerCustomOrderEnabled()) choosePosition(section);
				else if (section.canHide()) {
					Preferences.setDrawerSectionVisible(section, !Preferences.isDrawerSectionVisible(section));
					invalidate();
				}
			});
		}

		@Override public ViewType getViewType() { return ViewType.DRAWER_SECTION; }
		@Override protected void extract(SharedPreferences preferences) {}
		@Override protected void persist(SharedPreferences preferences) {}

		@Override
		public ViewHolder createViewHolder(ViewGroup parent) {
			ViewHolder holder = super.createViewHolder(parent);
			int size = (int) (48 * ResourceUtils.obtainDensity(parent));
			CheckBox visible = new CheckBox(parent.getContext());
			ThemeEngine.applyStyle(visible);
			InterfaceAppearance.check(visible);
			LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(size, size);
			checkParams.setMarginEnd((int) (8 * ResourceUtils.obtainDensity(parent)));
			((LinearLayout) holder.view).addView(visible, 0, checkParams);
			// Rows grow with the existing settings text scale; no clipping of long section names.
			holder.title.setMaxLines(Integer.MAX_VALUE);
			holder.title.setEllipsize(null);
			ImageView handle = new ImageView(parent.getContext());
			handle.setImageResource(R.drawable.ic_drag_handle);
			handle.setImageTintList(ColorStateList.valueOf(ThemeEngine.getTheme(parent.getContext()).accent));
			int padding = (int) (12 * ResourceUtils.obtainDensity(parent.getContext()));
			handle.setPadding(padding, padding, padding, padding);
			holder.widgetFrame.addView(handle, size, size);
			return new SectionViewHolder(holder, visible, handle);
		}

		@SuppressLint("ClickableViewAccessibility") // Row click provides the same ordering via an accessible dialog.
		@Override
		public void bindViewHolder(ViewHolder holder) {
			super.bindViewHolder(holder);
			SectionViewHolder rowHolder = (SectionViewHolder) holder;
			rowHolder.visible.setOnCheckedChangeListener(null);
			rowHolder.visible.setChecked(Preferences.isDrawerSectionVisible(section));
			rowHolder.visible.setEnabled(section.canHide());
			rowHolder.visible.setContentDescription(getString(R.string.drawer_show_section__format,
					getString(section.titleResId)));
			rowHolder.visible.setOnCheckedChangeListener((button, checked) -> {
				Preferences.setDrawerSectionVisible(section, checked);
				invalidate();
			});
			boolean reorder = Preferences.isDrawerCustomOrderEnabled();
			holder.widgetFrame.setVisibility(reorder ? View.VISIBLE : View.GONE);
			View handle = rowHolder.handle;
			handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			handle.setOnTouchListener((view, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN && list != null) {
					RecyclerView.ViewHolder row = list.findContainingViewHolder(holder.view);
					if (row != null && isSection(row)) dragHelper.startDrag(row);
				}
				return true;
			});
		}
	}
}
