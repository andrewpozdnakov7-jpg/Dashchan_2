package com.mishiranu.dashchan.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.preference.BaseListFragment;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.HeaderItemDecoration;
import com.mishiranu.dashchan.widget.SimpleViewHolder;
import com.mishiranu.dashchan.widget.ViewFactory;

public class RedditSectionsFragment extends BaseListFragment {
	private static final Section[] SECTIONS = {
			new Section(R.string.reddit_navigation, R.string.reddit_section_home,
					RedditWebReaderFragment.HOME_URL),
			new Section(R.string.reddit_navigation, R.string.reddit_section_popular,
					RedditWebReaderFragment.POPULAR_URL),
			new Section(R.string.reddit_navigation, R.string.reddit_section_all,
					RedditWebReaderFragment.ALL_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_ask,
					RedditWebReaderFragment.ASK_REDDIT_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_mildly_infuriating,
					RedditWebReaderFragment.MILDLY_INFURIATING_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_today_i_learned,
					RedditWebReaderFragment.TODAY_I_LEARNED_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_world_news,
					RedditWebReaderFragment.WORLD_NEWS_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_technology,
					RedditWebReaderFragment.TECHNOLOGY_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_android,
					RedditWebReaderFragment.ANDROID_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_gaming,
					RedditWebReaderFragment.GAMING_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_science,
					RedditWebReaderFragment.SCIENCE_URL),
			new Section(R.string.reddit_popular_communities, R.string.reddit_section_movies,
					RedditWebReaderFragment.MOVIES_URL)
	};

	@Override
	public boolean isPrimaryNavigationContent() {
		return true;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		SectionsAdapter adapter = new SectionsAdapter(section -> ((FragmentHandler) requireActivity())
				.pushFragment(RedditWebReaderFragment.newInstance(section.url)));
		getRecyclerView().setAdapter(adapter);
		getRecyclerView().addItemDecoration(new HeaderItemDecoration(adapter::getItemHeader));
		getRecyclerView().setItemAnimator(null);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.reddit_sections), null);
	}

	@Override
	protected DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(position + 1 < SECTIONS.length &&
				SECTIONS[position].categoryResId != SECTIONS[position + 1].categoryResId);
	}

	private static class Section {
		public final int categoryResId;
		public final int titleResId;
		public final String url;

		public Section(int categoryResId, int titleResId, String url) {
			this.categoryResId = categoryResId;
			this.titleResId = titleResId;
			this.url = url;
		}
	}

	private static class SectionsAdapter extends RecyclerView.Adapter<SimpleViewHolder> {
		public interface Callback {
			void onSectionClick(Section section);
		}

		private final Callback callback;

		public SectionsAdapter(Callback callback) {
			this.callback = callback;
		}

		@NonNull
		@Override
		public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			SimpleViewHolder holder = new SimpleViewHolder(ViewFactory.makeSingleLineListItem(parent));
			holder.itemView.setOnClickListener(v -> {
				int position = holder.getAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					callback.onSectionClick(SECTIONS[position]);
				}
			});
			return holder;
		}

		@Override
		public void onBindViewHolder(@NonNull SimpleViewHolder holder, int position) {
			((TextView) holder.itemView).setText(SECTIONS[position].titleResId);
		}

		@Override
		public int getItemCount() {
			return SECTIONS.length;
		}

		public String getItemHeader(Context context, int position) {
			Section section = SECTIONS[position];
			return position == 0 || SECTIONS[position - 1].categoryResId != section.categoryResId
					? context.getString(section.categoryResId) : null;
		}
	}
}
