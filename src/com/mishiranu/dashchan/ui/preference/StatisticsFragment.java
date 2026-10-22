package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Bundle;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.PostDateFormatter;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.AppearanceTypography;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class StatisticsFragment extends BaseListFragment {
	@Override protected boolean useStandardServiceAppearance() { return true; }

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ExpandedLayout root = (ExpandedLayout) super.onCreateView(inflater, container, savedInstanceState);
		if (InterfaceAppearance.isEnabled(root.getContext())) {
			RecyclerView recycler = getRecyclerView();
			ViewGroup.LayoutParams params = recycler.getLayoutParams();
			root.removeView(recycler);
			TableLayout table = new TableLayout(root.getContext(), recycler);
			root.addView(table, 0, params);
			root.setRecyclerView(recycler);
		}
		return root;
	}

	/** A single horizontal viewport owns ALL columns and the header; vertical scrolling
	 * remains RecyclerView's responsibility. There are no per-row scroll containers. */
	static final class TableLayout extends HorizontalScrollView {
		final RecyclerView recycler;
		TableLayout(Context context, RecyclerView recycler) {
			super(context); this.recycler = recycler; setFillViewport(true);
			addView(recycler, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		}
		@Override protected void onMeasure(int widthSpec, int heightSpec) {
			int viewport = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
			int width = viewport;
			if (recycler.getAdapter() instanceof Adapter) {
				Adapter adapter = (Adapter) recycler.getAdapter();
				width = adapter.measureTable(getContext(), viewport);
			}
			recycler.getLayoutParams().width = width;
			if (width <= viewport) scrollTo(0, 0);
			super.onMeasure(widthSpec, heightSpec);
		}
		@Override protected void measureChildWithMargins(View child, int parentWidthSpec, int widthUsed,
				int parentHeightSpec, int heightUsed) {
			// HorizontalScrollView normally passes UNSPECIFIED width to its child.
			// RecyclerView must instead receive our exact shared table width.
			ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
			int height = getChildMeasureSpec(parentHeightSpec, getPaddingTop() + getPaddingBottom()
					+ lp.topMargin + lp.bottomMargin + heightUsed, lp.height);
			child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, lp.width), MeasureSpec.EXACTLY), height);
		}
	}
	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		long startTime = StatisticsStorage.getInstance().getStartTime();
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.statistics),
				startTime > 0 ? getString(R.string.since_date__format,
						new PostDateFormatter(requireContext()).formatDateTime(startTime)) : null);

		ArrayList<Adapter.ListItem> listItems = new ArrayList<>();
		listItems.add(new Adapter.ListItem(null, getString(R.string.views),
				getString(R.string.posts), getString(R.string.threads)));

		HashMap<String, StatisticsStorage.StatisticsItem> statisticsItems = StatisticsStorage.getInstance().getItems();
		int totalThreadsViewed = 0;
		int totalPostsSent = 0;
		int totalThreadsCreated = 0;
		for (HashMap.Entry<String, StatisticsStorage.StatisticsItem> entry : statisticsItems.entrySet()) {
			ChanConfiguration.Statistics statistics = Chan.get(entry.getKey()).configuration.safe().obtainStatistics();
			StatisticsStorage.StatisticsItem statisticsItem = entry.getValue();
			if (statistics.threadsViewed && statisticsItem.threadsViewed > 0) {
				totalThreadsViewed += statisticsItem.threadsViewed;
			}
			if (statistics.postsSent && statisticsItem.postsSent > 0) {
				totalPostsSent += statisticsItem.postsSent;
			}
			if (statistics.threadsCreated && statisticsItem.threadsCreated > 0) {
				totalThreadsCreated += statisticsItem.threadsCreated;
			}
		}
		listItems.add(new Adapter.ListItem(getString(R.string.total), Integer.toString(totalThreadsViewed),
				Integer.toString(totalPostsSent), Integer.toString(totalThreadsCreated)));

		for (Chan chan : ChanManager.getInstance().getAvailableChans()) {
			StatisticsStorage.StatisticsItem statisticsItem = statisticsItems.get(chan.name);
			if (statisticsItem != null) {
				ChanConfiguration.Statistics statistics = chan.configuration.safe().obtainStatistics();
				if (statistics.threadsViewed && statistics.postsSent && statistics.threadsCreated) {
					int threadsViewed = statistics.threadsViewed ? statisticsItem.threadsViewed : -1;
					int postsSent = statistics.postsSent ? statisticsItem.postsSent : -1;
					int threadsCreated = statistics.threadsCreated ? statisticsItem.threadsCreated : -1;
					String title = chan.configuration.getTitle();
					if (StringUtils.isEmpty(title)) {
						title = chan.name;
					}
					listItems.add(new Adapter.ListItem(title, Integer.toString(threadsViewed),
							Integer.toString(postsSent), Integer.toString(threadsCreated)));
				}
			}
		}

		getRecyclerView().setAdapter(new Adapter(listItems));
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_clear, 0, R.string.clear)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionDelete))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_clear) {
			StatisticsStorage.getInstance().clear();
			((FragmentHandler) requireActivity()).removeFragment();
		}
		return super.onMenuItemSelected(item);
	}

	static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
		public static class ListItem {
			public final String text1;
			public final String text2;
			public final String text3;
			public final String text4;

			public ListItem(String text1, String text2, String text3, String text4) {
				this.text1 = text1;
				this.text2 = text2;
				this.text3 = text3;
				this.text4 = text4;
			}
		}

		private static class ViewHolder extends RecyclerView.ViewHolder {
			public final TextView text1;
			public final TextView text2;
			public final TextView text3;
			public final TextView text4;

			public ViewHolder(@NonNull View itemView, TextView text1, TextView text2, TextView text3, TextView text4) {
				super(itemView);
				this.text1 = text1;
				this.text2 = text2;
				this.text3 = text3;
				this.text4 = text4;
			}
		}

		private final List<ListItem> listItems;
		private int[] columns;
		private int[] naturalColumns;
		private AppearanceTypography.Configuration measuredTypography;

		int measureTable(Context context, int viewport) {
			if (!InterfaceAppearance.isEnabled(context)) { columns = null; return viewport; }
			AppearanceTypography.Configuration typography = new AppearanceTypography.Configuration(context);
			if (naturalColumns == null || !typography.equals(measuredTypography)) {
				TextView probe = new TextView(context);
				naturalColumns = new int[4];
				for (int row = 0; row < listItems.size(); row++) {
					AppearanceTypography.apply(probe, row == 0 ? AppearanceTypography.Role.LABEL_LARGE : AppearanceTypography.Role.BODY_MEDIUM);
					ListItem item = listItems.get(row);
					String[] text = {item.text1, item.text2, item.text3, item.text4};
					for (int i = 0; i < text.length; i++) naturalColumns[i] = Math.max(naturalColumns[i],
							(int) Math.ceil(Layout.getDesiredWidth(text[i] != null ? text[i] : "", probe.getPaint())) + 2);
				}
				measuredTypography = typography;
			}
			int padding = Math.round(16 * ResourceUtils.obtainDensity(context));
			int gap = Math.round(8 * ResourceUtils.obtainDensity(context));
			columns = StatisticsColumnWidths.resolve(naturalColumns, Math.max(0, viewport - 2 * padding - 3 * gap));
			int width = 2 * padding + 3 * gap;
			for (int column : columns) width += column;
			return Math.max(viewport, width);
		}

		private final class TableRow extends LinearLayout {
			private boolean material;
			TableRow(Context context) { super(context); }
			@Override protected void onMeasure(int widthSpec, int heightSpec) {
				if (InterfaceAppearance.isEnabled(getContext()) && columns != null) {
					material = true;
					int padding = Math.round(16 * ResourceUtils.obtainDensity(this));
					int gap = Math.round(8 * ResourceUtils.obtainDensity(this));
					setPaddingRelative(padding, gap, padding, gap); setMinimumHeight(Math.round(48 * ResourceUtils.obtainDensity(this)));
					getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
					for (int i = 0; i < 4; i++) {
						LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) getChildAt(i).getLayoutParams();
						lp.width = columns[i]; lp.weight = 0; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
						lp.leftMargin = 0; lp.rightMargin = 0; lp.setMarginStart(i == 0 ? 0 : gap); lp.setMarginEnd(0);
					}
				} else if (material) {
					material = false;
					int padding = Math.round(16 * ResourceUtils.obtainDensity(this));
					int gap = Math.round(8 * ResourceUtils.obtainDensity(this));
					setPadding(padding - gap, 0, padding, 0); setMinimumHeight(0);
					getLayoutParams().height = Math.round(48 * ResourceUtils.obtainDensity(this));
					for (int i = 0; i < 4; i++) {
						LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, i == 0 ? 3 : 2);
						lp.leftMargin = gap; getChildAt(i).setLayoutParams(lp);
					}
				}
				super.onMeasure(widthSpec, heightSpec);
			}
		}

		public Adapter(List<ListItem> listItems) {
			this.listItems = listItems;
		}

		@Override
		public int getItemCount() {
			return listItems.size();
		}

		private TextView addTextView(LinearLayout parent, boolean end, float weight, int padding) {
			TextView textView = new TextView(parent.getContext());
			TextViewCompat.setTextAppearance(textView, ResourceUtils.getResourceId(textView.getContext(),
					android.R.attr.textAppearanceListItem,
					android.R.style.TextAppearance_Medium));
			ThemeEngine.applyStyle(textView);
			textView.setSingleLine(true);
			textView.setEllipsize(TextUtils.TruncateAt.END);
			textView.setGravity(Gravity.CENTER_VERTICAL | (end ? Gravity.END : Gravity.START));
			LinearLayout.LayoutParams layoutParams = new LinearLayout
					.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
			layoutParams.leftMargin = padding;
			parent.addView(textView, layoutParams);
			InterfaceAppearance.serviceTableCell(textView, false);
			return textView;
		}

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LinearLayout linearLayout = new TableRow(parent.getContext());
			linearLayout.setOrientation(LinearLayout.HORIZONTAL);
			float density = ResourceUtils.obtainDensity(linearLayout);
			int outerPadding = (int) (16f * density);
			int innerPadding = (int) (8f * density);
			linearLayout.setPadding(outerPadding - innerPadding, 0, outerPadding, 0);
			TextView text1 = addTextView(linearLayout, false, 3f, innerPadding);
			TextView text2 = addTextView(linearLayout, true, 2f, innerPadding);
			TextView text3 = addTextView(linearLayout, true, 2f, innerPadding);
			TextView text4 = addTextView(linearLayout, true, 2f, innerPadding);
			linearLayout.setLayoutParams(new RecyclerView.LayoutParams
					(RecyclerView.LayoutParams.MATCH_PARENT, InterfaceAppearance.isEnabled(parent.getContext())
							? RecyclerView.LayoutParams.WRAP_CONTENT : (int) (48f * density)));
			return new ViewHolder(linearLayout, text1, text2, text3, text4);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			ListItem listItem = listItems.get(position);
			holder.text1.setText(listItem.text1);
			holder.text2.setText(listItem.text2);
			holder.text3.setText(listItem.text3);
			holder.text4.setText(listItem.text4);
			holder.itemView.getLayoutParams().height = InterfaceAppearance.isEnabled(holder.itemView.getContext())
					? RecyclerView.LayoutParams.WRAP_CONTENT : (int) (48f * ResourceUtils.obtainDensity(holder.itemView));
			for (TextView text : new TextView[]{holder.text1, holder.text2, holder.text3, holder.text4})
				InterfaceAppearance.serviceTableCell(text, position == 0);
		}
	}
}
