package com.mishiranu.dashchan.ui.navigator.page;

import android.view.Menu;
import android.view.MenuItem;
import androidx.recyclerview.widget.LinearLayoutManager;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.async.ReadMyPostsTask;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.InstanceDialog;
import com.mishiranu.dashchan.ui.navigator.adapter.MyPostsAdapter;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.PaddedRecyclerView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyPostsPage extends ListPage implements MyPostsAdapter.Callback, ReadMyPostsTask.Callback {
	private static final int REPLY_HISTORY_LIMIT = 50;
	private static final int REPLY_HISTORY_COMMENT_LIMIT = 100;

	private final ArrayDeque<MyPostsStorage.ThreadKey> checkQueue = new ArrayDeque<>();
	private final Runnable storageObserver = () -> {
		updateList();
		updateOptionsMenu();
	};

	private ReadMyPostsTask task;
	private int checkErrors;

	private MyPostsAdapter getAdapter() {
		return (MyPostsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
		MyPostsAdapter adapter = new MyPostsAdapter(getContext(), this);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				adapter::configureDivider));
		recyclerView.setItemAnimator(null);
		MyPostsStorage.getInstance().getObservable().register(storageObserver);
		updateList();
	}

	@Override
	protected void onDestroy() {
		MyPostsStorage.getInstance().getObservable().unregister(storageObserver);
		if (task != null) {
			task.cancel();
			task = null;
		}
		checkQueue.clear();
	}

	@Override
	public String obtainTitle() {
		return getString(R.string.replies);
	}

	private void updateList() {
		if (!isRunning()) {
			return;
		}
		MyPostsStorage storage = MyPostsStorage.getInstance();
		List<MyPostsStorage.ReplyItem> replies = storage.getUnreadReplies();
		getAdapter().setReplies(replies);
		if (replies.isEmpty()) {
			int message = !Preferences.isTrackMyPostsEnabled() ? R.string.reply_tracking_is_disabled
					: storage.getPosts().isEmpty() ? R.string.tracked_replies_is_empty : R.string.no_unread_replies;
			switchError(message);
		} else {
			switchList();
		}
	}

	@Override
	public void onItemClick(MyPostsStorage.ReplyItem reply) {
		openReply(reply);
	}

	@Override
	public boolean onItemLongClick(MyPostsStorage.ReplyItem reply) {
		new InstanceDialog(getFragmentManager(), null, provider -> {
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.stop_tracking_replies, () -> MyPostsStorage.getInstance()
					.remove(reply.chanName, reply.boardName, reply.threadNumber, reply.trackedPostNumber));
			return dialogMenu.create();
		});
		return true;
	}

	private void openReply(MyPostsStorage.ReplyItem reply) {
		MyPostsStorage.getInstance().markThreadRead(reply.chanName, reply.boardName, reply.threadNumber);
		getUiManager().navigator().navigatePosts(reply.chanName, reply.boardName,
				reply.threadNumber, reply.postNumber, null);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_refresh, 0, R.string.check_replies)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_reply_history, 1, R.string.reply_history);
		menu.add(0, R.id.menu_mark_replies_read, 2, R.string.mark_all_replies_read);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		MenuItem refresh = menu.findItem(R.id.menu_refresh);
		if (refresh != null) {
			refresh.setEnabled(task == null && Preferences.isTrackMyPostsEnabled());
		}
		MenuItem history = menu.findItem(R.id.menu_reply_history);
		if (history != null) {
			history.setEnabled(!MyPostsStorage.getInstance().getRecentReplies(1).isEmpty());
		}
		MenuItem markRead = menu.findItem(R.id.menu_mark_replies_read);
		if (markRead != null) {
			markRead.setEnabled(MyPostsStorage.getInstance().getUnreadCount() > 0);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_refresh) {
			startCheck();
			return true;
		} else if (item.getItemId() == R.id.menu_reply_history) {
			showReplyHistory();
			return true;
		} else if (item.getItemId() == R.id.menu_mark_replies_read) {
			markAllRepliesRead();
			return true;
		}
		return false;
	}

	private void showReplyHistory() {
		List<MyPostsStorage.ReplyItem> replies = MyPostsStorage.getInstance()
				.getRecentReplies(REPLY_HISTORY_LIMIT);
		if (replies.isEmpty()) {
			ClickableToast.show(R.string.reply_history_is_empty);
			return;
		}
		new InstanceDialog(getFragmentManager(), null, provider -> {
			DialogMenu dialogMenu = new DialogMenu(provider.getContext())
					.setTitle(getString(R.string.reply_history));
			for (MyPostsStorage.ReplyItem reply : replies) {
				dialogMenu.add(formatHistoryItem(reply), () -> openReply(reply));
			}
			return dialogMenu.create();
		});
	}

	private void markAllRepliesRead() {
		MyPostsStorage storage = MyPostsStorage.getInstance();
		HashMap<MyPostsStorage.ThreadKey, ArrayList<PostNumber>> repliesByThread = new HashMap<>();
		for (MyPostsStorage.ReplyItem reply : storage.getUnreadReplies()) {
			MyPostsStorage.ThreadKey key = new MyPostsStorage.ThreadKey(reply.chanName,
					reply.boardName, reply.threadNumber);
			ArrayList<PostNumber> postNumbers = repliesByThread.get(key);
			if (postNumbers == null) {
				postNumbers = new ArrayList<>();
				repliesByThread.put(key, postNumbers);
			}
			postNumbers.add(reply.postNumber);
		}
		storage.markAllRead();
		for (Map.Entry<MyPostsStorage.ThreadKey, ArrayList<PostNumber>> entry : repliesByThread.entrySet()) {
			MyPostsStorage.ThreadKey key = entry.getKey();
			WatcherNotifications.cancelReplies(getContext(), key.chanName, key.boardName,
					key.threadNumber, entry.getValue());
		}
	}

	private String formatHistoryItem(MyPostsStorage.ReplyItem reply) {
		String comment = StringUtils.isEmptyOrWhitespace(reply.comment)
				? getString(R.string.tracked_post_number__format, reply.postNumber)
				: reply.comment.replace('\n', ' ').trim();
		if (comment.length() > REPLY_HISTORY_COMMENT_LIMIT) {
			comment = comment.substring(0, REPLY_HISTORY_COMMENT_LIMIT).trim() + "…";
		}
		Chan chan = Chan.get(reply.chanName);
		String boardTitle = chan.configuration.getBoardTitle(reply.boardName);
		String location = chan.configuration.formatBoardTitle(reply.boardName, boardTitle);
		return (reply.unread ? "● " : "") + comment + " — " + chan.configuration.getTitle()
				+ " " + location;
	}

	private void startCheck() {
		if (task != null || !Preferences.isTrackMyPostsEnabled()) {
			return;
		}
		checkQueue.clear();
		checkQueue.addAll(MyPostsStorage.getInstance().getActiveThreadKeys());
		if (checkQueue.isEmpty()) {
			updateList();
			return;
		}
		checkErrors = 0;
		switchProgress();
		updateOptionsMenu();
		startNextCheck();
	}

	private void startNextCheck() {
		MyPostsStorage.ThreadKey key = checkQueue.pollFirst();
		if (key == null) {
			task = null;
			updateOptionsMenu();
			updateList();
			if (checkErrors > 0) {
				ClickableToast.show(getString(R.string.replies_check_finished_with_errors__format, checkErrors));
			}
			return;
		}
		task = new ReadMyPostsTask(this, key);
		task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
	}

	@Override
	public void onReadMyPostsComplete(MyPostsStorage.ThreadKey key, ReadMyPostsTask.Result result) {
		task = null;
		if (!result.success) {
			checkErrors++;
			if (result.threadDeleted) {
				MyPostsStorage.getInstance().setThreadDeleted(key, true);
			}
		}
		startNextCheck();
	}
}
