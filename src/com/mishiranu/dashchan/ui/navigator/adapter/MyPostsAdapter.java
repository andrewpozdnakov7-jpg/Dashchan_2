package com.mishiranu.dashchan.ui.navigator.adapter;

import android.content.Context;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.util.ListViewUtils;
import com.mishiranu.dashchan.util.PostDateFormatter;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.SimpleViewHolder;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.List;

public class MyPostsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	public interface Callback extends ListViewUtils.SimpleCallback<MyPostsStorage.ReplyItem> {}

	private final Context context;
	private final Callback callback;
	private final PostDateFormatter postDateFormatter;
	private final ArrayList<MyPostsStorage.ReplyItem> replies = new ArrayList<>();

	public MyPostsAdapter(Context context, Callback callback) {
		this.context = context;
		this.callback = callback;
		postDateFormatter = new PostDateFormatter(context);
		setHasStableIds(true);
	}

	public void setReplies(List<MyPostsStorage.ReplyItem> replies) {
		this.replies.clear();
		this.replies.addAll(replies);
		notifyDataSetChanged();
	}

	private MyPostsStorage.ReplyItem getItem(int position) {
		return replies.get(position);
	}

	@Override
	public int getItemCount() {
		return replies.size();
	}

	@Override
	public long getItemId(int position) {
		MyPostsStorage.ReplyItem reply = getItem(position);
		long result = reply.chanName.hashCode();
		result = 31L * result + reply.boardName.hashCode();
		result = 31L * result + reply.threadNumber.hashCode();
		return 31L * result + reply.postNumber.hashCode();
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return ListViewUtils.bind(new SimpleViewHolder(ViewFactory.makeTwoLinesListItem(parent,
				ViewFactory.FEATURE_TEXT2_END).view), true, this::getItem, callback);
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		MyPostsStorage.ReplyItem reply = getItem(position);
		ViewFactory.TwoLinesViewHolder viewHolder = (ViewFactory.TwoLinesViewHolder) holder.itemView.getTag();
		String comment = StringUtils.isEmptyOrWhitespace(reply.comment)
				? context.getString(R.string.tracked_post_number__format, reply.postNumber)
				: reply.comment.replace('\n', ' ').trim();
		viewHolder.text1.setText(comment);
		Chan chan = Chan.get(reply.chanName);
		String boardTitle = chan.configuration.getBoardTitle(reply.boardName);
		String location = StringUtils.formatBoardTitle(reply.chanName, reply.boardName, boardTitle);
		location = chan.configuration.getTitle() + " — " + location + " · >>" + reply.trackedPostNumber;
		if (reply.threadDeleted) {
			location += " · " + context.getString(R.string.thread_is_deleted);
		}
		viewHolder.text2.setText(location);
		viewHolder.text2End.setText(reply.time > 0L ? postDateFormatter.formatDate(reply.time) : "");
	}

	public DividerItemDecoration.Configuration configureDivider(
			DividerItemDecoration.Configuration configuration, int position) {
		return configuration.need(true);
	}
}
