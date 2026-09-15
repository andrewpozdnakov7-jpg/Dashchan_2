package com.mishiranu.dashchan.ui.navigator.adapter;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.PostDateFormatter;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.SimpleViewHolder;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MyPostsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int VIEW_TYPE_TABS = 0;
	private static final int VIEW_TYPE_ITEM = 1;
	private static final int VIEW_TYPE_EMPTY = 2;
	private static final int VIEW_TYPE_MORE = 3;

	public enum Mode {REPLIES, MY_POSTS}

	public static final class Item {
		public final MyPostsStorage.ReplyItem reply;
		public final MyPostsStorage.TrackedPost post;

		private Item(MyPostsStorage.ReplyItem reply, MyPostsStorage.TrackedPost post) {
			this.reply = reply;
			this.post = post;
		}

		public static Item reply(MyPostsStorage.ReplyItem reply) {
			return new Item(reply, null);
		}

		public static Item post(MyPostsStorage.TrackedPost post) {
			return new Item(null, post);
		}
	}

	public interface Callback extends ListViewUtils.SimpleCallback<Item> {
		void onModeSelected(Mode mode);
		void onLoadMorePosts();
	}

	private final Context context;
	private final Callback callback;
	private final PostDateFormatter postDateFormatter;
	private final ArrayList<Item> items = new ArrayList<>();
	private Mode mode = Mode.REPLIES;
	private String emptyText;
	private boolean hasMorePosts;

	public MyPostsAdapter(Context context, Callback callback) {
		this.context = context;
		this.callback = callback;
		postDateFormatter = new PostDateFormatter(context);
		setHasStableIds(true);
	}

	public void setMode(Mode mode) {
		if (this.mode != mode) {
			this.mode = mode;
			notifyItemChanged(0);
		}
	}

	public void setReplies(List<MyPostsStorage.ReplyItem> replies, String emptyText) {
		hasMorePosts = false;
		items.clear();
		for (MyPostsStorage.ReplyItem reply : replies) {
			items.add(Item.reply(reply));
		}
		this.emptyText = emptyText;
		notifyDataSetChanged();
	}

	public void setPosts(List<MyPostsStorage.TrackedPost> posts, String emptyText, boolean hasMorePosts) {
		this.hasMorePosts = hasMorePosts && !posts.isEmpty();
		items.clear();
		for (MyPostsStorage.TrackedPost post : posts) {
			items.add(Item.post(post));
		}
		this.emptyText = emptyText;
		notifyDataSetChanged();
	}

	private Item getItem(int position) {
		return items.get(position - 1);
	}

	public int getPostCount() {
		return mode == Mode.MY_POSTS ? items.size() : 0;
	}

	public void appendPosts(List<MyPostsStorage.TrackedPost> posts, boolean hasMore) {
		if (mode != Mode.MY_POSTS || !hasMorePosts) {
			return;
		}
		int position = items.size() + 1;
		for (MyPostsStorage.TrackedPost post : posts) {
			items.add(Item.post(post));
		}
		hasMorePosts = hasMore;
		// Insert before the existing footer; leave all previously bound rows untouched.
		if (!posts.isEmpty()) {
			notifyItemRangeInserted(position, posts.size());
		}
		if (!hasMore) {
			notifyItemRemoved(position + posts.size());
		}
	}

	@Override
	public int getItemCount() {
		return Math.max(items.size(), 1) + 1 + (hasMorePosts ? 1 : 0);
	}

	@Override
	public int getItemViewType(int position) {
		if (hasMorePosts && position == getItemCount() - 1) {
			return VIEW_TYPE_MORE;
		}
		return position == 0 ? VIEW_TYPE_TABS : items.isEmpty() ? VIEW_TYPE_EMPTY : VIEW_TYPE_ITEM;
	}

	@Override
	public long getItemId(int position) {
		if (position == 0) {
			return Long.MIN_VALUE;
		}
		if (getItemViewType(position) == VIEW_TYPE_MORE) {
			return Long.MIN_VALUE + 2L;
		}
		if (items.isEmpty()) {
			return Long.MIN_VALUE + 1L;
		}
		Item item = getItem(position);
		String chanName;
		String boardName;
		String threadNumber;
		Object postNumber;
		long type;
		if (item.reply != null) {
			MyPostsStorage.ReplyItem reply = item.reply;
			chanName = reply.chanName;
			boardName = reply.boardName;
			threadNumber = reply.threadNumber;
			postNumber = reply.postNumber;
			type = 1L;
		} else {
			MyPostsStorage.TrackedPost post = item.post;
			chanName = post.chanName;
			boardName = post.boardName;
			threadNumber = post.threadNumber;
			postNumber = post.postNumber;
			type = 2L;
		}
		long result = chanName.hashCode();
		result = 31L * result + boardName.hashCode();
		result = 31L * result + threadNumber.hashCode();
		result = 31L * result + postNumber.hashCode();
		return 31L * result + type;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		if (viewType == VIEW_TYPE_TABS) {
			return makeTabsViewHolder(parent);
		}
		if (viewType == VIEW_TYPE_MORE) {
			TextView textView = (TextView) ViewFactory.makeSingleLineListItem(parent);
			textView.setGravity(Gravity.CENTER);
			textView.setText(R.string.my_posts_show_more);
			textView.setOnClickListener(v -> callback.onLoadMorePosts());
			return new SimpleViewHolder(textView);
		}
		if (viewType == VIEW_TYPE_EMPTY) {
			TextView textView = (TextView) ViewFactory.makeSingleLineListItem(parent);
			textView.setGravity(Gravity.CENTER);
			textView.setAlpha(0.7f);
			textView.setBackground(null);
			return new SimpleViewHolder(textView);
		}
		ViewFactory.TwoLinesViewHolder views = ViewFactory.makeTwoLinesListItem(parent,
				ViewFactory.FEATURE_TEXT2_END | ViewFactory.FEATURE_MULTILINE_TITLE);
		views.text1.setMaxLines(5);
		views.text1.setEllipsize(TextUtils.TruncateAt.END);
		int verticalPadding = (int) (8f * ResourceUtils.obtainDensity(parent) + 0.5f);
		views.view.setPaddingRelative(views.view.getPaddingStart(), verticalPadding,
				views.view.getPaddingEnd(), verticalPadding);
		return ListViewUtils.bind(new ItemViewHolder(views.view, views.text1.getTypeface()), true,
				this::getItem, callback);
	}

	private TabsViewHolder makeTabsViewHolder(ViewGroup parent) {
		float density = ResourceUtils.obtainDensity(parent);
		LinearLayout layout = new LinearLayout(parent.getContext());
		layout.setOrientation(LinearLayout.HORIZONTAL);
		layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		TabView replies = makeTab(parent, layout, R.string.replies, density);
		TabView myPosts = makeTab(parent, layout, R.string.my_posts, density);
		TabsViewHolder holder = new TabsViewHolder(layout, replies, myPosts);
		replies.container.setOnClickListener(v -> callback.onModeSelected(Mode.REPLIES));
		myPosts.container.setOnClickListener(v -> callback.onModeSelected(Mode.MY_POSTS));
		return holder;
	}

	private TabView makeTab(ViewGroup parent, LinearLayout root, int textResId, float density) {
		LinearLayout container = new LinearLayout(parent.getContext());
		container.setOrientation(LinearLayout.VERTICAL);
		ViewUtils.setSelectableItemBackground(container);
		TextView text = (TextView) ViewFactory.makeSingleLineListItem(parent);
		text.setBackground(null);
		text.setText(textResId);
		text.setGravity(Gravity.CENTER);
		text.setPadding(0, 0, 0, 0);
		container.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				(int) (46f * density + 0.5f)));
		View indicator = new View(parent.getContext());
		container.addView(indicator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				(int) (2f * density + 0.5f)));
		root.addView(container, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		return new TabView(container, text, indicator);
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		if (getItemViewType(position) == VIEW_TYPE_MORE) {
			return;
		}
		if (holder instanceof TabsViewHolder) {
			TabsViewHolder tabs = (TabsViewHolder) holder;
			bindTab(tabs.replies, mode == Mode.REPLIES);
			bindTab(tabs.myPosts, mode == Mode.MY_POSTS);
			return;
		}
		if (items.isEmpty()) {
			((TextView) holder.itemView).setText(emptyText);
			return;
		}
		Item item = getItem(position);
		ItemViewHolder itemHolder = (ItemViewHolder) holder;
		ViewFactory.TwoLinesViewHolder viewHolder = (ViewFactory.TwoLinesViewHolder) holder.itemView.getTag();
		if (item.reply != null) {
			bindReply(itemHolder, viewHolder, item.reply);
		} else {
			bindPost(itemHolder, viewHolder, item.post);
		}
	}

	private void bindTab(TabView tab, boolean selected) {
		int accent = ThemeEngine.getTheme(tab.text.getContext()).accent;
		tab.text.setTextColor(selected ? accent
				: ResourceUtils.getColor(tab.text.getContext(), android.R.attr.textColorSecondary));
		tab.indicator.setBackgroundColor(selected ? accent : Color.TRANSPARENT);
	}

	private void bindReply(ItemViewHolder itemHolder, ViewFactory.TwoLinesViewHolder viewHolder,
			MyPostsStorage.ReplyItem reply) {
		String comment = obtainComment(reply.comment, reply.postNumber.toString());
		// The reply target is already shown in the location line. Keep other references and
		// the stored message intact; only omit a repeated leading target in this preview.
		comment = comment.replaceFirst("^(?:" + Pattern.quote(">>" + reply.trackedPostNumber)
				+ "(?=\\s|$)\\s*)+", "");
		viewHolder.text1.setText(comment);
		viewHolder.text1.setVisibility(comment.isEmpty() ? View.GONE : View.VISIBLE);
		viewHolder.text1.setTypeface(reply.unread ? ResourceUtils.TYPEFACE_MEDIUM : itemHolder.regularTypeface);
		bindLocation(viewHolder, reply.chanName, reply.boardName, reply.trackedPostNumber.toString(),
				reply.threadDeleted, reply.time);
		float alpha = reply.unread ? 1f : 0.56f;
		viewHolder.text1.setAlpha(alpha);
		viewHolder.text2.setAlpha(alpha);
		viewHolder.text2End.setAlpha(alpha);
	}

	private void bindPost(ItemViewHolder itemHolder, ViewFactory.TwoLinesViewHolder viewHolder,
			MyPostsStorage.TrackedPost post) {
		viewHolder.text1.setText(obtainComment(post.comment, post.postNumber.toString()));
		viewHolder.text1.setVisibility(View.VISIBLE);
		viewHolder.text1.setTypeface(itemHolder.regularTypeface);
		bindLocation(viewHolder, post.chanName, post.boardName, post.postNumber.toString(),
				post.threadDeleted, post.time);
		float alpha = post.trackingActive ? 1f : 0.56f;
		viewHolder.text1.setAlpha(alpha);
		viewHolder.text2.setAlpha(alpha);
		viewHolder.text2End.setAlpha(alpha);
	}

	private String obtainComment(String comment, String postNumber) {
		return StringUtils.isEmptyOrWhitespace(comment)
				? context.getString(R.string.tracked_post_number__format, postNumber) : comment.trim();
	}

	private void bindLocation(ViewFactory.TwoLinesViewHolder viewHolder, String chanName, String boardName,
			String postNumber, boolean threadDeleted, long time) {
		Chan chan = Chan.get(chanName);
		String boardTitle = chan.configuration.getBoardTitle(boardName);
		String location = chan.configuration.formatBoardTitle(boardName, boardTitle);
		location = chan.configuration.getTitle() + " \u2014 " + location + " \u00b7 >>" + postNumber;
		if (threadDeleted) {
			location += " \u00b7 " + context.getString(R.string.thread_is_deleted);
		}
		viewHolder.text2.setText(location);
		viewHolder.text2End.setText(time > 0L ? postDateFormatter.formatDate(time) : "");
	}

	public DividerItemDecoration.Configuration configureDivider(
			DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(true);
	}

	private static final class TabView {
		public final LinearLayout container;
		public final TextView text;
		public final View indicator;

		private TabView(LinearLayout container, TextView text, View indicator) {
			this.container = container;
			this.text = text;
			this.indicator = indicator;
		}
	}

	private static final class TabsViewHolder extends RecyclerView.ViewHolder {
		public final TabView replies;
		public final TabView myPosts;

		private TabsViewHolder(View itemView, TabView replies, TabView myPosts) {
			super(itemView);
			this.replies = replies;
			this.myPosts = myPosts;
		}
	}

	private static final class ItemViewHolder extends SimpleViewHolder {
		public final android.graphics.Typeface regularTypeface;

		private ItemViewHolder(View itemView, android.graphics.Typeface regularTypeface) {
			super(itemView);
			this.regularTypeface = regularTypeface;
		}
	}
}
