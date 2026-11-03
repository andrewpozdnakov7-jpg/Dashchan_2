package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.util.PostDateFormatter;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class StatisticsFragment extends BaseListFragment {
	@Override protected boolean useStandardServiceAppearance() { return true; }

	@Override
	protected DividerItemDecoration.Configuration configureDivider(
			DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(!InterfaceAppearance.isEnabled(requireContext()));
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		long startTime = StatisticsStorage.getInstance().getStartTime();
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.statistics),
				startTime > 0 ? getString(R.string.since_date__format,
						new PostDateFormatter(requireContext()).formatDateTime(startTime)) : null);

		ArrayList<Adapter.ListItem> listItems = new ArrayList<>();
		String repliesPeriod = getString(R.string.statistics_replies_since__format,
				new PostDateFormatter(requireContext()).formatDate(StatisticsStorage.getInstance().getRepliesStartTime()));

		HashMap<String, StatisticsStorage.StatisticsItem> statisticsItems = StatisticsStorage.getInstance().getItems();
		int totalThreadsViewed = 0;
		int totalPostsSent = 0;
		int totalThreadsCreated = 0;
		long totalRepliesReceived = 0;
		for (HashMap.Entry<String, StatisticsStorage.StatisticsItem> entry : statisticsItems.entrySet()) {
			ChanConfiguration.Statistics statistics = Chan.get(entry.getKey()).configuration.safe().obtainStatistics();
			StatisticsStorage.StatisticsItem statisticsItem = entry.getValue();
			if (statistics != null && statistics.threadsViewed && statisticsItem.threadsViewed > 0) {
				totalThreadsViewed += statisticsItem.threadsViewed;
			}
			if (statistics != null && statistics.postsSent && statisticsItem.postsSent > 0) {
				totalPostsSent += statisticsItem.postsSent;
			}
			if (statistics != null && statistics.threadsCreated && statisticsItem.threadsCreated > 0) {
				totalThreadsCreated += statisticsItem.threadsCreated;
			}
			totalRepliesReceived += statisticsItem.repliesReceived;
		}
		listItems.add(new Adapter.ListItem(getString(R.string.total), Integer.toString(totalThreadsViewed),
				Integer.toString(totalPostsSent), Integer.toString(totalThreadsCreated), Long.toString(totalRepliesReceived)));

		for (Chan chan : ChanManager.getInstance().getAvailableChans()) {
			StatisticsStorage.StatisticsItem statisticsItem = statisticsItems.get(chan.name);
			if (statisticsItem != null) {
				ChanConfiguration.Statistics statistics = chan.configuration.safe().obtainStatistics();
				if (statisticsItem.repliesReceived > 0 || statistics != null
						&& (statistics.threadsViewed || statistics.postsSent || statistics.threadsCreated)) {
					String threadsViewed = statistics != null && statistics.threadsViewed
							? Integer.toString(statisticsItem.threadsViewed) : "—";
					String postsSent = statistics != null && statistics.postsSent
							? Integer.toString(statisticsItem.postsSent) : "—";
					String threadsCreated = statistics != null && statistics.threadsCreated
							? Integer.toString(statisticsItem.threadsCreated) : "—";
					String title = chan.configuration.getTitle();
					if (StringUtils.isEmpty(title)) {
						title = chan.name;
					}
					listItems.add(new Adapter.ListItem(title, threadsViewed,
							postsSent, threadsCreated, Long.toString(statisticsItem.repliesReceived)));
				}
			}
		}

		getRecyclerView().setAdapter(new Adapter(listItems, repliesPeriod));
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
			public final String text5;

			public ListItem(String text1, String text2, String text3, String text4, String text5) {
				this.text1 = text1;
				this.text2 = text2;
				this.text3 = text3;
				this.text4 = text4;
				this.text5 = text5;
			}
		}

		static final class ViewHolder extends RecyclerView.ViewHolder {
			final StatisticsCardView card;
			ViewHolder(StatisticsCardView card) { super(card); this.card = card; }
		}

		private final List<ListItem> listItems;
		private final String repliesPeriod;

		Adapter(List<ListItem> listItems, String repliesPeriod) {
			this.listItems = listItems;
			this.repliesPeriod = repliesPeriod;
		}

		@Override public int getItemCount() { return listItems.size(); }

		@NonNull
		@Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(new StatisticsCardView(parent.getContext()));
		}

		@Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			ListItem item = listItems.get(position);
			holder.card.bind(item.text1, new String[]{item.text2, item.text3, item.text4, item.text5},
					position == 0 ? repliesPeriod : null);
		}
	}
}
