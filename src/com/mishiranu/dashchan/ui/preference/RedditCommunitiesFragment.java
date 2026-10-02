package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.RedditCommunities;
import com.mishiranu.dashchan.content.storage.RedditCommunities.Category;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.Collections;

/** Editor for local Reddit bookmarks. No network requests or account changes. */
public class RedditCommunitiesFragment extends BaseListFragment {
	private ArrayList<Category> categories;
	private final ArrayList<Row> rows = new ArrayList<>();
	private RecyclerView.Adapter<Holder> adapter;
	private AlertDialog dialog;

	private static class Row {
		final Category category;
		final String name;
		Row(Category category, String name) { this.category = category; this.name = name; }
	}

	private static class Holder extends RecyclerView.ViewHolder {
		final TextView text;
		Holder(TextView text) { super(text); this.text = text; }
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		categories = RedditCommunities.load();
		adapter = new RecyclerView.Adapter<Holder>() {
			@Override public int getItemCount() { return rows.size() + 2; }
			@NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
				Holder holder = new Holder((TextView) ViewFactory.makeSingleLineListItem(parent));
				holder.itemView.setOnClickListener(v -> {
					int position = holder.getBindingAdapterPosition();
					if (position == RecyclerView.NO_POSITION) return;
					if (position == 0) editCommunity(null, null);
					else if (position == 1) editCategory(null);
					else {
						Row row = rows.get(position - 2);
						if (row.name == null) categoryActions(row.category);
						else communityActions(row.category, row.name);
					}
				});
				return holder;
			}
			@Override public void onBindViewHolder(@NonNull Holder holder, int position) {
				Row row = position >= 2 ? rows.get(position - 2) : null;
				holder.text.setText(position == 0 ? getString(R.string.reddit_add_community)
						: position == 1 ? getString(R.string.reddit_add_category)
						: row.name == null ? title(row.category) + " (" + row.category.communities.size() + ")"
						: "r/" + row.name);
				holder.text.setTypeface(row != null && row.name == null
						? ResourceUtils.TYPEFACE_MEDIUM : android.graphics.Typeface.DEFAULT);
				int padding = (int) (ResourceUtils.obtainDensity(holder.itemView) * (row != null && row.name != null ? 32 : 16));
				holder.text.setPaddingRelative(padding, holder.text.getPaddingTop(),
						(int) (16 * ResourceUtils.obtainDensity(holder.itemView)), holder.text.getPaddingBottom());
			}
		};
		getRecyclerView().setAdapter(adapter);
		getRecyclerView().setItemAnimator(null);
		rebuild();
	}

	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.reddit_my_communities), null);
	}

	private String title(Category category) {
		return RedditCommunities.UNGROUPED.equals(category.id) ? getString(R.string.reddit_ungrouped) : category.title;
	}

	private void rebuild() {
		rows.clear();
		for (Category category : categories) {
			rows.add(new Row(category, null));
			for (String name : category.communities) rows.add(new Row(category, name));
		}
		adapter.notifyDataSetChanged();
	}

	private void changed() { RedditCommunities.save(categories); rebuild(); }
	private void show(AlertDialog next) {
		if (dialog != null) dialog.dismiss();
		dialog = next;
		dialog.show();
	}

	private LinearLayout form() {
		LinearLayout form = new LinearLayout(requireContext());
		form.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (24 * ResourceUtils.obtainDensity(form));
		form.setPadding(padding, 0, padding, 0);
		return form;
	}

	private void editCategory(Category category) {
		LinearLayout form = form();
		EditText text = new EditText(requireContext());
		text.setSingleLine(true);
		text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
		text.setHint(R.string.reddit_category_name);
		if (category != null) text.setText(category.title);
		form.addView(text);
		AlertDialog edit = new AlertDialog.Builder(requireContext()).setTitle(category == null
				? R.string.reddit_add_category : R.string.reddit_rename_category).setView(form)
				.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
		show(edit);
		edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String value = text.getText().toString().trim();
			if (value.isEmpty()) { text.setError(getString(R.string.reddit_category_name)); return; }
			for (Category other : categories) {
				if (other != category && title(other).equalsIgnoreCase(value)) {
					text.setError(getString(R.string.reddit_duplicate)); return;
				}
			}
			if (category == null) categories.add(new Category(value)); else category.title = value;
			changed(); edit.dismiss();
		});
	}

	private void editCommunity(Category source, String original) {
		LinearLayout form = form();
		EditText text = new EditText(requireContext());
		text.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
				| android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		text.setMinLines(1);
		text.setMaxLines(3);
		text.setGravity(Gravity.TOP | Gravity.START);
		text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2048)});
		text.setHint(R.string.reddit_community_hint);
		if (original != null) text.setText(original);
		form.addView(text);
		Spinner spinner = new Spinner(requireContext());
		ArrayList<String> titles = new ArrayList<>();
		for (Category category : categories) titles.add(title(category));
		ArrayAdapter<String> choices = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, titles);
		choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(choices);
		spinner.setContentDescription(getString(R.string.reddit_category_name));
		spinner.setSelection(source != null ? categories.indexOf(source) : 0);
		form.addView(spinner);
		AlertDialog edit = new AlertDialog.Builder(requireContext()).setTitle(original == null
				? R.string.reddit_add_community : R.string.reddit_edit_community).setView(form)
				.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
		show(edit);
		edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String name = RedditCommunities.normalize(text.getText().toString());
			if (name == null) { text.setError(getString(R.string.reddit_invalid_community)); return; }
			for (Category category : categories) for (String other : category.communities) {
				if (!(category == source && other.equals(original)) && other.equalsIgnoreCase(name)) {
					text.setError(getString(R.string.reddit_duplicate)); return;
				}
			}
			Category target = categories.get(spinner.getSelectedItemPosition());
			if (source == target && original != null) source.communities.set(source.communities.indexOf(original), name);
			else {
				if (source != null) source.communities.remove(original);
				target.communities.add(name);
			}
			changed(); edit.dismiss();
		});
	}

	private void communityActions(Category category, String name) {
		show(new AlertDialog.Builder(requireContext()).setTitle("r/" + name).setItems(new String[] {
				getString(R.string.reddit_edit_community), getString(R.string.reddit_move_up),
				getString(R.string.reddit_move_down), getString(R.string.reddit_remove_community)}, (d, which) -> {
			if (which == 0) editCommunity(category, name);
			else if (which == 3) {
				show(new AlertDialog.Builder(requireContext()).setMessage(R.string.reddit_remove_community_confirm)
						.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
						(d2, w) -> { category.communities.remove(name); changed(); }).create());
			} else {
				int index = category.communities.indexOf(name), target = index + (which == 1 ? -1 : 1);
				if (target >= 0 && target < category.communities.size()) {
					Collections.swap(category.communities, index, target); changed();
				}
			}
		}).create());
	}

	private void categoryActions(Category category) {
		if (RedditCommunities.UNGROUPED.equals(category.id)) { editCommunity(category, null); return; }
		show(new AlertDialog.Builder(requireContext()).setTitle(title(category)).setItems(new String[] {
				getString(R.string.reddit_rename_category), getString(R.string.reddit_move_up),
				getString(R.string.reddit_move_down), getString(R.string.reddit_remove_category)}, (d, which) -> {
			if (which == 0) editCategory(category);
			else if (which == 3) {
				show(new AlertDialog.Builder(requireContext()).setMessage(R.string.reddit_remove_category_confirm)
						.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (d2, w) -> {
							categories.get(0).communities.addAll(category.communities);
							categories.remove(category); changed();
						}).create());
			} else {
				int index = categories.indexOf(category), target = index + (which == 1 ? -1 : 1);
				if (target > 0 && target < categories.size()) { Collections.swap(categories, index, target); changed(); }
			}
		}).create());
	}

	@Override public void onDestroyView() {
		if (dialog != null) { dialog.dismiss(); dialog = null; }
		adapter = null;
		rows.clear();
		super.onDestroyView();
	}
}
