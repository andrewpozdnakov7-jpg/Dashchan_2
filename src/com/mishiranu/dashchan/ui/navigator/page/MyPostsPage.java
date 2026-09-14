package com.mishiranu.dashchan.ui.navigator.page;

import android.app.AlertDialog;
import android.view.Menu;
import android.view.MenuItem;
import androidx.recyclerview.widget.LinearLayoutManager;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MyPostsPage extends ListPage implements MyPostsAdapter.Callback, ReadMyPostsTask.Callback {
	private static final int MAX_CONCURRENT_CHECKS = 3;
	private static final ExtraFactory<RetainableExtra> RETAINABLE_EXTRA_FACTORY = RetainableExtra::new;

	private static final class RetainableExtra implements Retainable {
		public MyPostsAdapter.Mode mode = MyPostsAdapter.Mode.REPLIES;
	}

	private final ArrayDeque<MyPostsStorage.ThreadKey> checkQueue = new ArrayDeque<>();
	private final HashMap<MyPostsStorage.ThreadKey, ReadMyPostsTask> tasks = new HashMap<>();
	private final HashSet<String> activeChanNames = new HashSet<>();
	private final Runnable storageObserver = () -> {
		updateList();
		updateOptionsMenu();
	};

	private int checkErrors;
	private MyPostsAdapter.Mode mode = MyPostsAdapter.Mode.REPLIES;

	private MyPostsAdapter getAdapter() {
		return (MyPostsAdapter) getRecyclerView().getAdapter();
	}

	@Override
	protected void onCreate() {
		mode = getRetainableExtra(RETAINABLE_EXTRA_FACTORY).mode;
		PaddedRecyclerView recyclerView = getRecyclerView();
		recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
		MyPostsAdapter adapter = new MyPostsAdapter(getContext(), this);
		recyclerView.setAdapter(adapter);
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(),
				adapter::configureDivider));
		recyclerView.setItemAnimator(null);
		MyPostsStorage.getInstance().getObservable().register(storageObserver);
	}

	@Override
	protected void onResume() {
		// onCreate runs while ListPage is INITIALIZED, so isRunning() is false there.
		// Populate the page after the lifecycle reaches STARTED/RESUMED.
		updateList();
	}

	@Override
	protected void onDestroy() {
		MyPostsStorage.getInstance().getObservable().unregister(storageObserver);
		for (ReadMyPostsTask task : tasks.values()) {
			task.cancel();
		}
		tasks.clear();
		activeChanNames.clear();
		checkQueue.clear();
	}

	private boolean isChecking() {
		return !tasks.isEmpty() || !checkQueue.isEmpty();
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
		MyPostsAdapter adapter = getAdapter();
		adapter.setMode(mode);
		if (mode == MyPostsAdapter.Mode.REPLIES) {
			List<MyPostsStorage.ReplyItem> replies = storage.getRecentReplies(0);
			int message = isChecking() ? R.string.loading__ellipsis
					: !Preferences.isTrackMyPostsEnabled() ? R.string.reply_tracking_is_disabled
					: storage.getPosts().isEmpty() ? R.string.tracked_replies_is_empty
					: R.string.no_replies_yet;
			adapter.setReplies(replies, getString(message));
		} else {
			adapter.setPosts(storage.getPosts(), getString(R.string.tracked_replies_is_empty));
		}
		switchList();
	}

	@Override
	public void onModeSelected(MyPostsAdapter.Mode mode) {
		if (this.mode != mode) {
			this.mode = mode;
			getRetainableExtra(RETAINABLE_EXTRA_FACTORY).mode = mode;
			updateList();
			getRecyclerView().scrollToPosition(0);
			updateOptionsMenu();
		}
	}

	@Override
	public void onItemClick(MyPostsAdapter.Item item) {
		if (item.reply != null) {
			openReply(item.reply);
		} else {
			openPost(item.post);
		}
	}

	@Override
	public boolean onItemLongClick(MyPostsAdapter.Item item) {
		MyPostsStorage.ReplyItem reply = item.reply;
		MyPostsStorage.TrackedPost post = item.post;
		new InstanceDialog(getFragmentManager(), null, provider -> {
			String chanName = reply != null ? reply.chanName : post.chanName;
			String boardName = reply != null ? reply.boardName : post.boardName;
			String threadNumber = reply != null ? reply.threadNumber : post.threadNumber;
			PostNumber postNumber = reply != null ? reply.trackedPostNumber : post.postNumber;
			DialogMenu dialogMenu = new DialogMenu(provider.getContext());
			dialogMenu.add(R.string.stop_tracking_replies, () -> MyPostsStorage.getInstance()
					.remove(chanName, boardName, threadNumber, postNumber));
			return dialogMenu.create();
		});
		return true;
	}

	private void openReply(MyPostsStorage.ReplyItem reply) {
		MyPostsStorage.getInstance().markThreadRead(reply.chanName, reply.boardName, reply.threadNumber);
		getUiManager().navigator().navigatePosts(reply.chanName, reply.boardName,
				reply.threadNumber, reply.postNumber, null);
	}

	private void openPost(MyPostsStorage.TrackedPost post) {
		getUiManager().navigator().navigatePosts(post.chanName, post.boardName,
				post.threadNumber, post.postNumber, null);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu) {
		menu.add(0, R.id.menu_refresh, 0, R.string.check_replies)
				.setIcon(getActionBarIcon(R.attr.iconActionRefresh))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, R.id.menu_mark_replies_read, 1, R.string.mark_all_replies_read);
		menu.add(0, R.id.menu_clear, 2, R.string.clear_reply_history);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		MenuItem refresh = menu.findItem(R.id.menu_refresh);
		if (refresh != null) {
			refresh.setEnabled(!isChecking() && Preferences.isTrackMyPostsEnabled());
		}
		MenuItem markRead = menu.findItem(R.id.menu_mark_replies_read);
		if (markRead != null) {
			markRead.setVisible(mode == MyPostsAdapter.Mode.REPLIES);
			markRead.setEnabled(MyPostsStorage.getInstance().getUnreadCount() > 0);
		}
		MenuItem clear = menu.findItem(R.id.menu_clear);
		if (clear != null) {
			clear.setVisible(mode == MyPostsAdapter.Mode.REPLIES);
			clear.setEnabled(!MyPostsStorage.getInstance().getRecentReplies(1).isEmpty());
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_refresh) {
			startCheck();
			return true;
		} else if (item.getItemId() == R.id.menu_mark_replies_read) {
			markAllRepliesRead();
			return true;
		} else if (item.getItemId() == R.id.menu_clear) {
			showClearReplyHistoryDialog();
			return true;
		}
		return false;
	}

	private void showClearReplyHistoryDialog() {
		new InstanceDialog(getFragmentManager(), null, provider -> new AlertDialog.Builder(provider.getContext())
				.setMessage(R.string.clear_reply_history__sentence)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> clearReplyHistory())
				.create());
	}

	private void clearReplyHistory() {
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
		storage.clearReplyHistory();
		for (Map.Entry<MyPostsStorage.ThreadKey, ArrayList<PostNumber>> entry : repliesByThread.entrySet()) {
			MyPostsStorage.ThreadKey key = entry.getKey();
			WatcherNotifications.cancelReplies(getContext(), key.chanName, key.boardName,
					key.threadNumber, entry.getValue());
		}
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

	private void startCheck() {
		if (isChecking() || !Preferences.isTrackMyPostsEnabled()) {
			return;
		}
		checkQueue.clear();
		checkQueue.addAll(MyPostsStorage.getInstance().getActiveThreadKeys());
		if (checkQueue.isEmpty()) {
			updateList();
			return;
		}
		checkErrors = 0;
		updateList();
		updateOptionsMenu();
		startNextChecks();
	}

	private void startNextChecks() {
		while (tasks.size() < MAX_CONCURRENT_CHECKS) {
			MyPostsStorage.ThreadKey key = null;
			Iterator<MyPostsStorage.ThreadKey> iterator = checkQueue.iterator();
			while (iterator.hasNext()) {
				MyPostsStorage.ThreadKey candidate = iterator.next();
				if (!activeChanNames.contains(candidate.chanName)) {
					key = candidate;
					iterator.remove();
					break;
				}
			}
			if (key == null) {
				break;
			}
			ReadMyPostsTask task = new ReadMyPostsTask(this, key);
			tasks.put(key, task);
			activeChanNames.add(key.chanName);
			task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
		}
		if (tasks.isEmpty() && checkQueue.isEmpty()) {
			updateOptionsMenu();
			updateList();
			if (checkErrors > 0) {
				ClickableToast.show(getString(R.string.replies_check_finished_with_errors__format, checkErrors));
			}
		}
	}

	@Override
	public void onReadMyPostsComplete(MyPostsStorage.ThreadKey key, ReadMyPostsTask.Result result) {
		if (tasks.remove(key) == null) {
			return;
		}
		activeChanNames.remove(key.chanName);
		if (!result.success) {
			checkErrors++;
			if (result.threadDeleted) {
				MyPostsStorage.getInstance().setThreadDeleted(key, true);
			}
		}
		// Publish partial results immediately; slow or unavailable forums must not hide replies
		// already obtained from other forums.
		updateList();
		startNextChecks();
	}
}
