package com.mishiranu.dashchan.ui;

import java.util.ArrayList;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.preference.BaseListFragment;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.HeaderItemDecoration;
import com.mishiranu.dashchan.widget.ViewFactory;

public class RedditSectionsFragment extends BaseListFragment {
	private static final String EXTRA_EXPANDED_CATEGORIES = "expandedCategories";

	private static final Section[] NAVIGATION = {
			new Section(R.string.reddit_section_home, RedditWebReaderFragment.HOME_URL),
			new Section(R.string.reddit_section_popular, RedditWebReaderFragment.POPULAR_URL),
			new Section(R.string.reddit_section_all, RedditWebReaderFragment.ALL_URL)
	};

	private static final Category[] CATEGORIES = {
			new Category(R.string.reddit_category_discussions, new String[] {
					"AskReddit", "NoStupidQuestions", "CasualConversation", "AmItheAsshole",
					"BestofRedditorUpdates", "todayilearned", "explainlikeimfive", "LifeProTips",
					"OutOfTheLoop", "TooAfraidToAsk", "AskMen", "AskWomen", "relationships",
					"relationship_advice", "confession", "tifu", "TrueOffMyChest", "YouShouldKnow"
			}),
			new Category(R.string.reddit_category_news, new String[] {
					"worldnews", "news", "europe", "geopolitics", "UpliftingNews", "politics",
					"PoliticalDiscussion", "NeutralPolitics", "inthenews", "Economics", "environment",
					"climate", "canada", "unitedkingdom"
			}),
			new Category(R.string.reddit_category_technology, new String[] {
					"technology", "Android", "programming", "gadgets", "hardware", "software",
					"privacy", "StableDiffusion", "linux", "windows", "apple", "iphone", "cybersecurity",
					"MachineLearning", "LocalLLaMA", "homelab"
			}),
			new Category(R.string.reddit_category_science, new String[] {
					"science", "space", "askscience", "dataisbeautiful", "history", "Documentaries",
					"AskHistorians", "math", "Physics", "chemistry", "biology", "astronomy", "geography",
					"languagelearning", "educationalgifs", "MapPorn"
			}),
			new Category(R.string.reddit_category_gaming, new String[] {
					"gaming", "Games", "pcgaming", "NintendoSwitch", "PS5", "Xbox", "Steam",
					"patientgamers", "GameDeals", "truegaming", "Minecraft", "FortNiteBR",
					"leagueoflegends", "DotA2", "GlobalOffensive", "Eldenring"
			}),
			new Category(R.string.reddit_category_entertainment, new String[] {
					"movies", "television", "Music", "anime", "books", "popculturechat", "moviesuggestions",
					"MovieDetails", "boxoffice", "NetflixBestOf", "marvelstudios", "StarWars", "harrypotter",
					"DC_Cinematic", "anime_irl", "manga", "podcasts", "Fauxmoi"
			}),
			new Category(R.string.reddit_category_humor, new String[] {
					"mildlyinfuriating", "funny", "pics", "videos", "memes", "Unexpected", "meirl",
					"dankmemes", "wholesomememes", "facepalm", "MadeMeSmile", "interestingasfuck",
					"Damnthatsinteresting", "nextfuckinglevel", "oddlysatisfying", "PublicFreakout",
					"ContagiousLaughter", "therewasanattempt"
			}),
			new Category(R.string.reddit_category_sports, new String[] {
					"sports", "soccer", "nfl", "nba", "formula1", "baseball", "hockey", "CFB",
					"CollegeBasketball", "tennis", "golf", "MMA", "boxing", "Cricket", "rugbyunion", "olympics"
			}),
			new Category(R.string.reddit_category_lifestyle, new String[] {
					"fitness", "loseit", "running", "bodyweightfitness", "Meditation", "selfimprovement",
					"socialskills", "dating_advice", "Parenting", "weddingplanning", "fashion",
					"malefashionadvice", "femalefashionadvice", "SkincareAddiction"
			}),
			new Category(R.string.reddit_category_food_home, new String[] {
					"food", "Cooking", "recipes", "Baking", "EatCheapAndHealthy", "MealPrepSunday",
					"cocktails", "Coffee", "tea", "DIY", "HomeImprovement", "gardening", "houseplants",
					"DesignMyRoom"
			}),
			new Category(R.string.reddit_category_creative, new String[] {
					"Art", "drawing", "crafts", "woodworking", "photography", "itookapicture",
					"photoshopbattles", "graphic_design", "writing", "WritingPrompts", "Poetry",
					"musicproduction", "WeAreTheMusicMakers", "3Dprinting"
			}),
			new Category(R.string.reddit_category_nature, new String[] {
					"aww", "cats", "dogs", "AnimalsBeingDerps", "NatureIsFuckingLit", "EarthPorn", "camping",
					"hiking", "backpacking", "Outdoors", "whatsthisplant", "birding", "Aquariums", "reptiles"
			}),
			new Category(R.string.reddit_category_finance_career, new String[] {
					"personalfinance", "financialindependence", "investing", "stocks", "wallstreetbets",
					"CryptoCurrency", "Entrepreneur", "smallbusiness", "careerguidance", "jobs", "resumes",
					"productivity"
			})
	};

	private final boolean[] expandedCategories = new boolean[CATEGORIES.length];
	private SectionsAdapter adapter;

	@Override
	public boolean isPrimaryNavigationContent() {
		return true;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		if (savedInstanceState != null) {
			boolean[] expanded = savedInstanceState.getBooleanArray(EXTRA_EXPANDED_CATEGORIES);
			if (expanded != null && expanded.length == expandedCategories.length) {
				System.arraycopy(expanded, 0, expandedCategories, 0, expanded.length);
			}
		}
		adapter = new SectionsAdapter(expandedCategories, section -> ((FragmentHandler) requireActivity())
				.pushFragment(RedditWebReaderFragment.newInstance(section.url)));
		getRecyclerView().setAdapter(adapter);
		getRecyclerView().addItemDecoration(new HeaderItemDecoration(adapter::getItemHeader));
		getRecyclerView().setItemAnimator(null);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.reddit_sections), null);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBooleanArray(EXTRA_EXPANDED_CATEGORIES, expandedCategories);
	}

	@Override
	protected DividerItemDecoration.Configuration configureDivider
			(DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(adapter != null && adapter.needDividerAfter(position));
	}

	private static class Section {
		public final int titleResId;
		public final String title;
		public final String url;

		public Section(int titleResId, String url) {
			this.titleResId = titleResId;
			this.title = null;
			this.url = url;
		}

		public Section(String subreddit) {
			this.titleResId = 0;
			this.title = "r/" + subreddit;
			this.url = "https://www.reddit.com/r/" + subreddit + "/";
		}
	}

	private static class Category {
		public final int titleResId;
		public final Section[] sections;

		public Category(int titleResId, String[] subreddits) {
			this.titleResId = titleResId;
			sections = new Section[subreddits.length];
			for (int i = 0; i < subreddits.length; i++) {
				sections[i] = new Section(subreddits[i]);
			}
		}
	}

	private static class Row {
		public static final int TYPE_NAVIGATION = 0;
		public static final int TYPE_CATEGORY = 1;
		public static final int TYPE_COMMUNITY = 2;

		public final int type;
		public final int categoryIndex;
		public final Section section;

		private Row(int type, int categoryIndex, Section section) {
			this.type = type;
			this.categoryIndex = categoryIndex;
			this.section = section;
		}
	}

	private static class SectionViewHolder extends RecyclerView.ViewHolder {
		public final TextView textView;

		public SectionViewHolder(TextView textView) {
			super(textView);
			this.textView = textView;
		}
	}

	private static class CategoryViewHolder extends RecyclerView.ViewHolder {
		public final TextView textView;
		public final ImageView iconView;

		public CategoryViewHolder(View itemView, TextView textView, ImageView iconView) {
			super(itemView);
			this.textView = textView;
			this.iconView = iconView;
		}
	}

	private static class SectionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		public interface Callback {
			void onSectionClick(Section section);
		}

		private final ArrayList<Row> rows = new ArrayList<>();
		private final boolean[] expandedCategories;
		private final Callback callback;

		public SectionsAdapter(boolean[] expandedCategories, Callback callback) {
			this.expandedCategories = expandedCategories;
			this.callback = callback;
			rebuildRows();
		}

		private void rebuildRows() {
			rows.clear();
			for (Section section : NAVIGATION) {
				rows.add(new Row(Row.TYPE_NAVIGATION, -1, section));
			}
			for (int i = 0; i < CATEGORIES.length; i++) {
				rows.add(new Row(Row.TYPE_CATEGORY, i, null));
				if (expandedCategories[i]) {
					for (Section section : CATEGORIES[i].sections) {
						rows.add(new Row(Row.TYPE_COMMUNITY, i, section));
					}
				}
			}
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (viewType == Row.TYPE_CATEGORY) {
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
				FrameLayout.LayoutParams iconLayoutParams = new FrameLayout.LayoutParams((int) (48f * density),
						(int) (48f * density), Gravity.END | Gravity.CENTER_VERTICAL);
				layout.addView(iconView, iconLayoutParams);
				CategoryViewHolder holder = new CategoryViewHolder(layout, textView, iconView);
				layout.setOnClickListener(v -> onItemClick(holder));
				return holder;
			}

			TextView textView = (TextView) ViewFactory.makeSingleLineListItem(parent);
			if (viewType == Row.TYPE_COMMUNITY) {
				float density = ResourceUtils.obtainDensity(parent);
				textView.setPaddingRelative((int) (32f * density), 0, (int) (16f * density), 0);
			}
			SectionViewHolder holder = new SectionViewHolder(textView);
			textView.setOnClickListener(v -> onItemClick(holder));
			return holder;
		}

		private void onItemClick(RecyclerView.ViewHolder holder) {
			int position = holder.getAdapterPosition();
			if (position == RecyclerView.NO_POSITION) {
				return;
			}
			Row row = rows.get(position);
			if (row.type == Row.TYPE_CATEGORY) {
				expandedCategories[row.categoryIndex] = !expandedCategories[row.categoryIndex];
				rebuildRows();
				notifyDataSetChanged();
			} else {
				callback.onSectionClick(row.section);
			}
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			Row row = rows.get(position);
			if (row.type == Row.TYPE_CATEGORY) {
				Category category = CATEGORIES[row.categoryIndex];
				boolean expanded = expandedCategories[row.categoryIndex];
				CategoryViewHolder categoryHolder = (CategoryViewHolder) holder;
				categoryHolder.textView.setText(category.titleResId);
				categoryHolder.iconView.setRotation(expanded ? 0f
						: holder.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 90f : -90f);
				String title = holder.itemView.getContext().getString(category.titleResId);
				categoryHolder.itemView.setContentDescription(holder.itemView.getContext().getString(expanded
						? R.string.collapse_reddit_category__format : R.string.expand_reddit_category__format, title));
			} else {
				Section section = row.section;
				SectionViewHolder sectionHolder = (SectionViewHolder) holder;
				if (section.titleResId != 0) {
					sectionHolder.textView.setText(section.titleResId);
				} else {
					sectionHolder.textView.setText(section.title);
				}
			}
		}

		@Override
		public int getItemViewType(int position) {
			return rows.get(position).type;
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		public boolean needDividerAfter(int position) {
			return position >= 0 && position + 1 < rows.size()
					&& rows.get(position + 1).type == Row.TYPE_CATEGORY;
		}

		public String getItemHeader(Context context, int position) {
			if (position == 0) {
				return context.getString(R.string.reddit_navigation);
			}
			Row row = rows.get(position);
			return row.type == Row.TYPE_CATEGORY && row.categoryIndex == 0
					? context.getString(R.string.reddit_popular_communities) : null;
		}
	}
}
