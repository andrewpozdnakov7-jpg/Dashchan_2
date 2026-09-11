package com.mishiranu.dashchan.ui.navigator.adapter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.ChanConfiguration;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.CursorAdapter;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.SimpleViewHolder;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BoardsAdapter extends CursorAdapter<ChanDatabase.BoardCursor, RecyclerView.ViewHolder> {
	public interface Callback extends ListViewUtils.SimpleCallback<ChanDatabase.BoardItem> {
		void onCategoryExpandedChanged(String category, boolean expanded);
	}

	private static final int TYPE_BOARD = 0;
	private static final int TYPE_CATEGORY = 1;

	private static class Row {
		public final int type;
		public final ChanDatabase.BoardItem boardItem;
		public final String category;
		public final int count;

		private Row(int type, ChanDatabase.BoardItem boardItem, String category, int count) {
			this.type = type;
			this.boardItem = boardItem;
			this.category = category;
			this.count = count;
		}

		public static Row board(ChanDatabase.BoardItem boardItem) {
			return new Row(TYPE_BOARD, boardItem, null, 0);
		}

		public static Row category(String category, int count) {
			return new Row(TYPE_CATEGORY, null, category, count);
		}
	}

	private static class CategoryViewHolder extends RecyclerView.ViewHolder {
		public final TextView textView;
		public final ImageView iconView;

		private CategoryViewHolder(View itemView, TextView textView, ImageView iconView) {
			super(itemView);
			this.textView = textView;
			this.iconView = iconView;
		}
	}

	private final Callback callback;
	private final ChanConfiguration configuration;
	private final boolean collapsibleCategories;
	private final Set<String> expandedCategories;
	private final ArrayList<Row> rows = new ArrayList<>();

	public BoardsAdapter(Callback callback, ChanConfiguration configuration, boolean collapsibleCategories,
			Set<String> expandedCategories) {
		this.callback = callback;
		this.configuration = configuration;
		this.collapsibleCategories = collapsibleCategories;
		this.expandedCategories = expandedCategories;
	}

	public boolean isRealEmpty() {
		ChanDatabase.BoardCursor cursor = getCursor();
		return cursor == null || !cursor.hasItems;
	}

	private ChanDatabase.BoardItem copyItem(int position) {
		Row row = rows.get(position);
		return row.boardItem != null ? row.boardItem.copy() : null;
	}

	@Override
	protected void onCursorChanged() {
		rebuildRows();
	}

	private void rebuildRows() {
		rows.clear();
		ChanDatabase.BoardCursor cursor = getCursor();
		if (cursor == null) return;
		ArrayList<ChanDatabase.BoardItem> items = new ArrayList<>(cursor.getCount());
		Map<String, Integer> categoryCounts = new HashMap<>();
		ChanDatabase.BoardItem reusable = new ChanDatabase.BoardItem();
		for (int i = 0; i < cursor.getCount(); i++) {
			cursor.moveToPosition(i);
			ChanDatabase.BoardItem item = reusable.update(cursor).copy();
			items.add(item);
			String category = StringUtils.emptyIfNull(item.category);
			categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
		}
		if (!collapsibleCategories || cursor.filtered) {
			for (ChanDatabase.BoardItem item : items) rows.add(Row.board(item));
			return;
		}
		String previousCategory = null;
		boolean first = true;
		for (ChanDatabase.BoardItem item : items) {
			String category = StringUtils.emptyIfNull(item.category);
			if (first || !CommonUtils.equals(previousCategory, category)) {
				rows.add(Row.category(category, categoryCounts.get(category)));
				previousCategory = category;
				first = false;
			}
			if (expandedCategories.contains(category)) rows.add(Row.board(item));
		}
	}

	@Override
	public int getItemCount() {
		return rows.size();
	}

	@Override
	public int getItemViewType(int position) {
		return rows.get(position).type;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		if (viewType == TYPE_CATEGORY) {
			Context context = parent.getContext();
			float density = ResourceUtils.obtainDensity(parent);
			FrameLayout layout = new FrameLayout(context);
			layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			ViewUtils.setSelectableItemBackground(layout);
			TextView textView = (TextView) ViewFactory.makeSingleLineListItem(parent);
			textView.setBackground(null);
			textView.setTypeface(ResourceUtils.TYPEFACE_MEDIUM);
			textView.setPaddingRelative((int) (16f * density), 0, (int) (56f * density), 0);
			layout.addView(textView);
			ImageView iconView = new ImageView(context);
			iconView.setImageResource(ResourceUtils.getResourceId(context, R.attr.iconButtonDropDown, 0));
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams((int) (48f * density),
					(int) (48f * density), Gravity.END | Gravity.CENTER_VERTICAL);
			layout.addView(iconView, params);
			CategoryViewHolder holder = new CategoryViewHolder(layout, textView, iconView);
			layout.setOnClickListener(v -> toggleCategory(holder));
			return holder;
		}
		TextView textView = (TextView) ViewFactory.makeSingleLineListItem(parent);
		if (collapsibleCategories) {
			float density = ResourceUtils.obtainDensity(parent);
			textView.setPaddingRelative((int) (32f * density), 0, (int) (16f * density), 0);
		}
		return ListViewUtils.bind(new SimpleViewHolder(textView), true, this::copyItem, callback);
	}

	private void toggleCategory(CategoryViewHolder holder) {
		int position = holder.getAdapterPosition();
		if (position == RecyclerView.NO_POSITION) return;
		Row row = rows.get(position);
		boolean expanded;
		if (expandedCategories.contains(row.category)) {
			expandedCategories.remove(row.category);
			expanded = false;
		} else {
			expandedCategories.add(row.category);
			expanded = true;
		}
		callback.onCategoryExpandedChanged(row.category, expanded);
		rebuildRows();
		notifyDataSetChanged();
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		Row row = rows.get(position);
		if (row.type == TYPE_CATEGORY) {
			boolean expanded = expandedCategories.contains(row.category);
			CategoryViewHolder categoryHolder = (CategoryViewHolder) holder;
			categoryHolder.textView.setText(row.category + " (" + row.count + ")");
			categoryHolder.iconView.setRotation(expanded ? 0f
					: holder.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 90f : -90f);
			categoryHolder.itemView.setContentDescription(holder.itemView.getContext().getString(expanded
					? R.string.collapse_board_category__format : R.string.expand_board_category__format,
					row.category));
		} else {
			((TextView) holder.itemView).setText(configuration.formatBoardTitle(
					row.boardItem.boardName, row.boardItem.extra1));
		}
	}

	public DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		boolean need = position + 1 < rows.size() && rows.get(position + 1).type == TYPE_CATEGORY;
		return configuration.need(need);
	}

	public String getItemHeader(int position) {
		ChanDatabase.BoardCursor cursor = getCursor();
		if (collapsibleCategories || cursor != null && cursor.filtered || position < 0 || position >= rows.size()) {
			return null;
		}
		if (position == 0) return StringUtils.nullIfEmpty(rows.get(0).boardItem.category);
		String previous = rows.get(position - 1).boardItem.category;
		String current = rows.get(position).boardItem.category;
		return CommonUtils.equals(previous, current) ? null : current;
	}
}
