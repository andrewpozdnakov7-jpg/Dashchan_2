package com.mishiranu.dashchan.content.storage;

import chan.text.JsonSerial;
import chan.text.ParseException;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.text.HtmlParser;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.WeakObservable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class MyPostsStorage extends StorageManager.Storage<List<MyPostsStorage.TrackedPost>> {
	private static final int MAX_STORED_POSTS = 1000;
	private static final String KEY_DATA = "data";
	private static final String KEY_CHAN_NAME = "chanName";
	private static final String KEY_BOARD_NAME = "boardName";
	private static final String KEY_THREAD_NUMBER = "threadNumber";
	private static final String KEY_POST_NUMBER = "postNumber";
	private static final String KEY_COMMENT = "comment";
	private static final String KEY_TIME = "time";
	private static final String KEY_LAST_CHECKED = "lastChecked";
	private static final String KEY_THREAD_DELETED = "threadDeleted";
	private static final String KEY_TRACKING_ACTIVE = "trackingActive";
	private static final String KEY_HIDDEN_FROM_MY_POSTS = "hiddenFromMyPosts";
	private static final String KEY_REPLIES = "replies";
	private static final String KEY_REPLIES_CLEARED_THROUGH = "repliesClearedThrough";
	private static final String KEY_UNREAD = "unread";
	private static final String KEY_PUSH_PENDING = "pushPending";

	private static final MyPostsStorage INSTANCE = new MyPostsStorage();

	public static MyPostsStorage getInstance() {
		return INSTANCE;
	}

	public static final class ThreadKey {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;

		public ThreadKey(String chanName, String boardName, String threadNumber) {
			this.chanName = chanName;
			this.boardName = StringUtils.emptyIfNull(boardName);
			this.threadNumber = threadNumber;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof ThreadKey) {
				ThreadKey key = (ThreadKey) o;
				return chanName.equals(key.chanName) && boardName.equals(key.boardName)
						&& threadNumber.equals(key.threadNumber);
			}
			return false;
		}

		@Override
		public int hashCode() {
			int result = chanName.hashCode();
			result = 31 * result + boardName.hashCode();
			return 31 * result + threadNumber.hashCode();
		}
	}

	public static final class Reply {
		public final PostNumber postNumber;
		public String comment;
		public final long time;
		public boolean unread;
		private boolean pushPending;

		private Reply(PostNumber postNumber, String comment, long time, boolean unread) {
			this.postNumber = postNumber;
			this.comment = comment;
			this.time = time;
			this.unread = unread;
		}

		private Reply(Reply reply) {
			this(reply.postNumber, reply.comment, reply.time, reply.unread);
			pushPending = reply.pushPending;
		}
	}

	public static final class TrackedPost {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final PostNumber postNumber;
		public String comment;
		public final long time;
		public long lastChecked;
		public boolean threadDeleted;
		public boolean trackingActive = true;
		private boolean hiddenFromMyPosts;
		public PostNumber repliesClearedThrough;
		public final ArrayList<Reply> replies = new ArrayList<>();

		private TrackedPost(String chanName, String boardName, String threadNumber, PostNumber postNumber,
				String comment, long time) {
			this.chanName = chanName;
			this.boardName = StringUtils.emptyIfNull(boardName);
			this.threadNumber = threadNumber;
			this.postNumber = postNumber;
			this.comment = comment;
			this.time = time;
		}

		private TrackedPost(TrackedPost trackedPost) {
			this(trackedPost, true);
		}

		private TrackedPost(TrackedPost trackedPost, boolean includeReplies) {
			this(trackedPost.chanName, trackedPost.boardName, trackedPost.threadNumber,
					trackedPost.postNumber, trackedPost.comment, trackedPost.time);
			lastChecked = trackedPost.lastChecked;
			threadDeleted = trackedPost.threadDeleted;
			trackingActive = trackedPost.trackingActive;
			hiddenFromMyPosts = trackedPost.hiddenFromMyPosts;
			repliesClearedThrough = trackedPost.repliesClearedThrough;
			if (includeReplies) {
				for (Reply reply : trackedPost.replies) {
					replies.add(new Reply(reply));
				}
			}
		}

		public ThreadKey getThreadKey() {
			return new ThreadKey(chanName, boardName, threadNumber);
		}

		public int getUnreadCount() {
			int count = 0;
			for (Reply reply : replies) {
				if (reply.unread) {
					count++;
				}
			}
			return count;
		}

		public Reply getLatestReply() {
			return replies.isEmpty() ? null : replies.get(replies.size() - 1);
		}
	}

	public static final class ReplyItem {
		public final String chanName;
		public final String boardName;
		public final String threadNumber;
		public final PostNumber trackedPostNumber;
		public final PostNumber postNumber;
		public final String comment;
		public final long time;
		public final boolean unread;
		public final boolean threadDeleted;

		private ReplyItem(TrackedPost trackedPost, Reply reply) {
			chanName = trackedPost.chanName;
			boardName = trackedPost.boardName;
			threadNumber = trackedPost.threadNumber;
			trackedPostNumber = trackedPost.postNumber;
			postNumber = reply.postNumber;
			comment = reply.comment;
			time = reply.time;
			unread = reply.unread;
			threadDeleted = trackedPost.threadDeleted;
		}
	}

	public enum AddReplyResult {ADDED, ALREADY_EXISTS, NOT_TRACKED}

	private final HashMap<String, TrackedPost> postsMap = new HashMap<>();
	private final ArrayList<TrackedPost> posts = new ArrayList<>();
	private final WeakObservable<Runnable> observable = new WeakObservable<>();
	private long revision;

	private MyPostsStorage() {
		super("my-posts", 1000, 5000);
		startRead();
		if (pruneInactivePosts()) {
			serialize();
		}
	}

	private static String makeKey(String chanName, String boardName, String threadNumber,
			PostNumber postNumber) {
		return chanName + "/" + StringUtils.emptyIfNull(boardName) + "/" + threadNumber + "/" + postNumber;
	}

	public WeakObservable<Runnable> getObservable() {
		return observable;
	}

	private void notifyChanged() {
		revision++;
		ConcurrentUtils.HANDLER.post(() -> {
			for (Runnable runnable : observable) {
				runnable.run();
			}
		});
	}

	private void sort() {
		Collections.sort(posts, Comparator.comparingLong((TrackedPost post) -> post.time).reversed());
	}

	// Soft retention limit: active tracking and unread replies always take precedence.
	// Call under the storage monitor, outside any iteration over posts.
	private boolean pruneInactivePosts() {
		boolean changed = false;
		for (int i = posts.size() - 1; i >= 0 && posts.size() > MAX_STORED_POSTS; i--) {
			TrackedPost post = posts.get(i);
			if (!post.trackingActive && post.getUnreadCount() == 0) {
				posts.remove(i);
				postsMap.remove(makeKey(post.chanName, post.boardName, post.threadNumber, post.postNumber));
				changed = true;
			}
		}
		return changed;
	}

	@Override
	public synchronized List<TrackedPost> onClone() {
		ArrayList<TrackedPost> result = new ArrayList<>(posts.size());
		for (TrackedPost post : posts) {
			result.add(new TrackedPost(post));
		}
		return result;
	}

	@Override
	public synchronized void onRead(InputStream input) throws IOException {
		try {
			JsonSerial.Reader reader = JsonSerial.reader(input);
			reader.startObject();
			while (!reader.endStruct()) {
				if (!KEY_DATA.equals(reader.nextName())) {
					reader.skip();
					continue;
				}
				reader.startArray();
				while (!reader.endStruct()) {
					String chanName = null;
					String boardName = null;
					String threadNumber = null;
					PostNumber postNumber = null;
					String comment = null;
					long time = 0L;
					long lastChecked = 0L;
					boolean threadDeleted = false;
					boolean trackingActive = true;
					boolean hiddenFromMyPosts = false;
					PostNumber repliesClearedThrough = null;
					ArrayList<Reply> replies = new ArrayList<>();
					reader.startObject();
					while (!reader.endStruct()) {
						switch (reader.nextName()) {
							case KEY_CHAN_NAME:
								chanName = reader.nextString();
								break;
							case KEY_BOARD_NAME:
								boardName = reader.nextString();
								break;
							case KEY_THREAD_NUMBER:
								threadNumber = reader.nextString();
								break;
							case KEY_POST_NUMBER:
								postNumber = PostNumber.parseNullable(reader.nextString());
								break;
							case KEY_COMMENT:
								comment = reader.nextString();
								break;
							case KEY_TIME:
								time = reader.nextLong();
								break;
							case KEY_LAST_CHECKED:
								lastChecked = reader.nextLong();
								break;
							case KEY_THREAD_DELETED:
								threadDeleted = reader.nextBoolean();
								break;
							case KEY_TRACKING_ACTIVE:
								trackingActive = reader.nextBoolean();
								break;
							case KEY_HIDDEN_FROM_MY_POSTS:
								hiddenFromMyPosts = reader.nextBoolean();
								break;
							case KEY_REPLIES_CLEARED_THROUGH:
								repliesClearedThrough = PostNumber.parseNullable(reader.nextString());
								break;
							case KEY_REPLIES:
								reader.startArray();
								while (!reader.endStruct()) {
									PostNumber replyNumber = null;
									String replyComment = null;
									long replyTime = 0L;
									boolean unread = false;
									boolean pushPending = false;
									reader.startObject();
									while (!reader.endStruct()) {
										switch (reader.nextName()) {
											case KEY_POST_NUMBER:
												replyNumber = PostNumber.parseNullable(reader.nextString());
												break;
											case KEY_COMMENT:
												replyComment = reader.nextString();
												break;
											case KEY_TIME:
												replyTime = reader.nextLong();
												break;
											case KEY_UNREAD:
												unread = reader.nextBoolean();
												break;
											case KEY_PUSH_PENDING:
												pushPending = reader.nextBoolean();
												break;
											default:
												reader.skip();
												break;
										}
									}
									if (replyNumber != null) {
										Reply reply = new Reply(replyNumber, replyComment, replyTime, unread);
										reply.pushPending = pushPending;
										replies.add(reply);
									}
								}
								break;
							default:
								reader.skip();
								break;
						}
					}
					if (!StringUtils.isEmpty(chanName) && !StringUtils.isEmpty(threadNumber)
							&& postNumber != null) {
						TrackedPost post = new TrackedPost(chanName, boardName, threadNumber,
								postNumber, comment, time);
						post.lastChecked = lastChecked;
						post.threadDeleted = threadDeleted;
						post.trackingActive = trackingActive;
						post.hiddenFromMyPosts = hiddenFromMyPosts;
						post.repliesClearedThrough = repliesClearedThrough;
						post.replies.addAll(replies);
						posts.add(post);
						postsMap.put(makeKey(chanName, boardName, threadNumber, postNumber), post);
					}
				}
			}
			sort();
		} catch (ParseException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void onWrite(List<TrackedPost> posts, OutputStream output) throws IOException {
		JsonSerial.Writer writer = JsonSerial.writer(output);
		writer.startObject();
		writer.name(KEY_DATA);
		writer.startArray();
		for (TrackedPost post : posts) {
			writer.startObject();
			writer.name(KEY_CHAN_NAME);
			writer.value(post.chanName);
			if (!StringUtils.isEmpty(post.boardName)) {
				writer.name(KEY_BOARD_NAME);
				writer.value(post.boardName);
			}
			writer.name(KEY_THREAD_NUMBER);
			writer.value(post.threadNumber);
			writer.name(KEY_POST_NUMBER);
			writer.value(post.postNumber.toString());
			if (!StringUtils.isEmpty(post.comment)) {
				writer.name(KEY_COMMENT);
				writer.value(post.comment);
			}
			writer.name(KEY_TIME);
			writer.value(post.time);
			writer.name(KEY_LAST_CHECKED);
			writer.value(post.lastChecked);
			writer.name(KEY_THREAD_DELETED);
			writer.value(post.threadDeleted);
			writer.name(KEY_TRACKING_ACTIVE);
			writer.value(post.trackingActive);
			writer.name(KEY_HIDDEN_FROM_MY_POSTS);
			writer.value(post.hiddenFromMyPosts);
			if (post.repliesClearedThrough != null) {
				writer.name(KEY_REPLIES_CLEARED_THROUGH);
				writer.value(post.repliesClearedThrough.toString());
			}
			writer.name(KEY_REPLIES);
			writer.startArray();
			for (Reply reply : post.replies) {
				writer.startObject();
				writer.name(KEY_POST_NUMBER);
				writer.value(reply.postNumber.toString());
				if (!StringUtils.isEmpty(reply.comment)) {
					writer.name(KEY_COMMENT);
					writer.value(reply.comment);
				}
				writer.name(KEY_TIME);
				writer.value(reply.time);
				writer.name(KEY_UNREAD);
				writer.value(reply.unread);
				if (reply.pushPending) {
					writer.name(KEY_PUSH_PENDING);
					writer.value(true);
				}
				writer.endObject();
			}
			writer.endArray();
			writer.endObject();
		}
		writer.endArray();
		writer.endObject();
		writer.flush();
	}

	public synchronized List<TrackedPost> getPosts() {
		return onClone();
	}

	public synchronized boolean hasPosts() {
		return !posts.isEmpty();
	}

	public synchronized boolean hasVisiblePosts() {
		for (TrackedPost post : posts) {
			if (!post.hiddenFromMyPosts) {
				return true;
			}
		}
		return false;
	}

	public static final class PostPreviewPage {
		public final List<TrackedPost> posts;
		public final boolean hasMore;
		public final long revision;

		private PostPreviewPage(List<TrackedPost> posts, boolean hasMore, long revision) {
			this.posts = posts;
			this.hasMore = hasMore;
			this.revision = revision;
		}
	}

	public synchronized boolean isPostPreviewCurrent(long revision) {
		return this.revision == revision;
	}

	/** One local UI page only: do not copy replies or the already displayed prefix. */
	public synchronized PostPreviewPage getVisiblePostPreviews(int offset, int limit) {
		if (offset < 0 || limit <= 0) {
			throw new IllegalArgumentException();
		}
		ArrayList<TrackedPost> result = new ArrayList<>();
		for (TrackedPost post : posts) {
			if (!post.hiddenFromMyPosts) {
				if (offset > 0) {
					offset--;
					continue;
				}
				if (result.size() == limit) {
					return new PostPreviewPage(result, true, revision);
				}
				result.add(new TrackedPost(post, false));
			}
		}
		return new PostPreviewPage(result, false, revision);
	}

	public synchronized void clearMyPostsList() {
		boolean changed = false;
		for (TrackedPost post : posts) {
			if (!post.hiddenFromMyPosts) {
				post.hiddenFromMyPosts = true;
				changed = true;
			}
		}
		// Clearing this view must not change tracking, replies or notification state.
		if (changed) {
			serialize();
			notifyChanged();
		}
	}

	public synchronized List<ReplyItem> getUnreadReplies() {
		return getReplies(true, 0);
	}

	public synchronized List<ReplyItem> getRecentReplies(int limit) {
		return getReplies(false, limit);
	}

	private List<ReplyItem> getReplies(boolean unreadOnly, int limit) {
		HashMap<String, ReplyItem> repliesMap = new HashMap<>();
		for (TrackedPost trackedPost : posts) {
			for (Reply reply : trackedPost.replies) {
				if (unreadOnly && !reply.unread) {
					continue;
				}
				String key = trackedPost.chanName + "/" + trackedPost.boardName + "/"
						+ trackedPost.threadNumber + "/" + reply.postNumber;
				ReplyItem existing = repliesMap.get(key);
				if (existing == null || reply.unread && !existing.unread) {
					repliesMap.put(key, new ReplyItem(trackedPost, reply));
				}
			}
		}
		ArrayList<ReplyItem> result = new ArrayList<>(repliesMap.values());
		Collections.sort(result, (first, second) -> {
			int compare = Long.compare(second.time, first.time);
			return compare != 0 ? compare : second.postNumber.compareTo(first.postNumber);
		});
		if (limit > 0 && result.size() > limit) {
			return new ArrayList<>(result.subList(0, limit));
		}
		return result;
	}

	public synchronized List<ThreadKey> getThreadKeys() {
		return getThreadKeys(true);
	}

	public synchronized List<ThreadKey> getActiveThreadKeys() {
		return getThreadKeys(false);
	}

	private List<ThreadKey> getThreadKeys(boolean includeDeleted) {
		LinkedHashSet<ThreadKey> keys = new LinkedHashSet<>();
		for (TrackedPost post : posts) {
			if (post.trackingActive && (includeDeleted || !post.threadDeleted)) {
				keys.add(post.getThreadKey());
			}
		}
		return new ArrayList<>(keys);
	}

	public synchronized boolean hasThread(ThreadKey key) {
		for (TrackedPost post : posts) {
			if (post.trackingActive && post.getThreadKey().equals(key)) {
				return true;
			}
		}
		return false;
	}

	public synchronized int getUnreadCount() {
		HashSet<String> unread = new HashSet<>();
		for (TrackedPost post : posts) {
			for (Reply reply : post.replies) {
				if (reply.unread) {
					unread.add(post.chanName + "/" + post.boardName + "/" + post.threadNumber
							+ "/" + reply.postNumber);
				}
			}
		}
		return unread.size();
	}

	public synchronized void add(String chanName, String boardName, String threadNumber,
			PostNumber postNumber, String comment, long time) {
		Objects.requireNonNull(chanName);
		Objects.requireNonNull(threadNumber);
		Objects.requireNonNull(postNumber);
		String key = makeKey(chanName, boardName, threadNumber, postNumber);
		TrackedPost existing = postsMap.get(key);
		if (existing == null) {
			TrackedPost post = new TrackedPost(chanName, boardName, threadNumber, postNumber,
					StringUtils.nullIfEmpty(comment), time > 0L ? time : System.currentTimeMillis());
			postsMap.put(key, post);
			posts.add(post);
			sort();
			pruneInactivePosts();
			serialize();
			notifyChanged();
		} else if (StringUtils.isEmpty(existing.comment) && !StringUtils.isEmpty(comment)) {
			existing.comment = comment;
			serialize();
			notifyChanged();
		}
	}

	public synchronized AddReplyResult addReply(String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, String comment, long time) {
		return addReply(chanName, boardName, threadNumber, trackedPostNumber, replyPostNumber, comment, time, false);
	}

	public synchronized AddReplyResult addReply(String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, String comment, long time, boolean fromPush) {
		Objects.requireNonNull(chanName);
		Objects.requireNonNull(threadNumber);
		Objects.requireNonNull(trackedPostNumber);
		Objects.requireNonNull(replyPostNumber);
		TrackedPost trackedPost = postsMap.get(makeKey(chanName, boardName, threadNumber, trackedPostNumber));
		if (trackedPost == null || !trackedPost.trackingActive) {
			return AddReplyResult.NOT_TRACKED;
		}
		if (trackedPost.repliesClearedThrough != null
				&& replyPostNumber.compareTo(trackedPost.repliesClearedThrough) <= 0) {
			return AddReplyResult.ALREADY_EXISTS;
		}
		for (Reply reply : trackedPost.replies) {
			if (reply.postNumber.equals(replyPostNumber)) {
				return AddReplyResult.ALREADY_EXISTS;
			}
		}
		Reply newReply = new Reply(replyPostNumber, StringUtils.nullIfEmpty(comment),
				time > 0L ? time : System.currentTimeMillis(), true);
		newReply.pushPending = fromPush;
		trackedPost.replies.add(newReply);
		Collections.sort(trackedPost.replies, Comparator.comparing(reply -> reply.postNumber));
		serialize();
		notifyChanged();
		return AddReplyResult.ADDED;
	}

	public synchronized Reply getPendingPushReply(String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber) {
		TrackedPost post = postsMap.get(makeKey(chanName, boardName, threadNumber, trackedPostNumber));
		if (post != null && post.trackingActive && (post.repliesClearedThrough == null
				|| replyPostNumber.compareTo(post.repliesClearedThrough) > 0)) {
			for (Reply reply : post.replies) {
				if (reply.postNumber.equals(replyPostNumber) && reply.pushPending && reply.unread) {
					return new Reply(reply);
				}
			}
		}
		return null;
	}

	public synchronized boolean isPushPending(String chanName, String boardName, String threadNumber,
			PostNumber replyPostNumber) {
		for (TrackedPost post : posts) {
			if (post.chanName.equals(chanName) && post.boardName.equals(StringUtils.emptyIfNull(boardName))
					&& post.threadNumber.equals(threadNumber)
					&& getPendingPushReply(chanName, boardName, threadNumber, post.postNumber, replyPostNumber) != null) {
				return true;
			}
		}
		return false;
	}

	public synchronized void completePushReply(String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber) {
		TrackedPost post = postsMap.get(makeKey(chanName, boardName, threadNumber, trackedPostNumber));
		if (post != null) {
			for (Reply reply : post.replies) {
				if (reply.postNumber.equals(replyPostNumber) && reply.pushPending) {
					reply.pushPending = false;
					serialize();
					return;
				}
			}
		}
	}

	public synchronized void remove(String chanName, String boardName, String threadNumber,
			PostNumber postNumber) {
		TrackedPost post = postsMap.remove(makeKey(chanName, boardName, threadNumber, postNumber));
		if (post != null) {
			posts.remove(post);
			serialize();
			notifyChanged();
		}
	}

	public synchronized void deactivateTracking(String chanName, String boardName, String threadNumber,
			PostNumber postNumber) {
		TrackedPost post = postsMap.get(makeKey(chanName, boardName, threadNumber, postNumber));
		if (post != null && post.trackingActive) {
			post.trackingActive = false;
			pruneInactivePosts();
			serialize();
			notifyChanged();
		}
	}

	public synchronized void deactivateAllTracking() {
		boolean changed = false;
		for (TrackedPost post : posts) {
			if (post.trackingActive) {
				post.trackingActive = false;
				changed = true;
			}
		}
		if (changed) {
			pruneInactivePosts();
			serialize();
			notifyChanged();
		}
	}

	public synchronized void markThreadRead(String chanName, String boardName, String threadNumber) {
		ThreadKey key = new ThreadKey(chanName, boardName, threadNumber);
		boolean changed = false;
		for (TrackedPost post : posts) {
			if (post.getThreadKey().equals(key)) {
				for (Reply reply : post.replies) {
					if (reply.unread) {
						reply.unread = false;
						changed = true;
					}
				}
			}
		}
		if (changed) {
			pruneInactivePosts();
			serialize();
			notifyChanged();
		}
	}

	public synchronized void markAllRead() {
		boolean changed = false;
		for (TrackedPost post : posts) {
			for (Reply reply : post.replies) {
				if (reply.unread) {
					reply.unread = false;
					changed = true;
				}
			}
		}
		if (changed) {
			pruneInactivePosts();
			serialize();
			notifyChanged();
		}
	}

	public synchronized void clearReplyHistory() {
		boolean changed = false;
		for (TrackedPost post : posts) {
			for (Reply reply : post.replies) {
				if (post.repliesClearedThrough == null
						|| reply.postNumber.compareTo(post.repliesClearedThrough) > 0) {
					post.repliesClearedThrough = reply.postNumber;
				}
			}
			if (!post.replies.isEmpty()) {
				post.replies.clear();
				changed = true;
			}
		}
		if (changed) {
			pruneInactivePosts();
			serialize();
			notifyChanged();
		}
	}

	public synchronized void setThreadDeleted(ThreadKey key, boolean deleted) {
		boolean changed = false;
		for (TrackedPost post : posts) {
			if (post.getThreadKey().equals(key) && post.threadDeleted != deleted) {
				post.threadDeleted = deleted;
				post.lastChecked = System.currentTimeMillis();
				changed = true;
			}
		}
		if (changed) {
			serialize();
			notifyChanged();
		}
	}

	public synchronized void updateThread(ThreadKey key, List<Post> threadPosts) {
		ArrayList<TrackedPost> tracked = new ArrayList<>();
		HashMap<PostNumber, TrackedPost> trackedByNumber = new HashMap<>();
		HashMap<TrackedPost, HashMap<PostNumber, Reply>> knownRepliesByTrackedPost = new HashMap<>();
		for (TrackedPost post : posts) {
			if (post.trackingActive && post.getThreadKey().equals(key)) {
				tracked.add(post);
				trackedByNumber.put(post.postNumber, post);
				HashMap<PostNumber, Reply> knownReplies = new HashMap<>();
				for (Reply reply : post.replies) {
					knownReplies.put(reply.postNumber, reply);
				}
				knownRepliesByTrackedPost.put(post, knownReplies);
			}
		}
		if (tracked.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (TrackedPost trackedPost : tracked) {
			if (trackedPost.threadDeleted) {
				trackedPost.threadDeleted = false;
				changed = true;
			}
			trackedPost.lastChecked = now;
		}
		for (Post post : threadPosts) {
			TrackedPost ownPost = trackedByNumber.get(post.number);
			if (ownPost != null && StringUtils.isEmpty(ownPost.comment)) {
				String comment = HtmlParser.clear(post.comment);
				if (!StringUtils.isEmpty(comment)) {
					ownPost.comment = comment;
					changed = true;
				}
			}
			Set<PostNumber> references = PostItem.collectReferences(null, post.comment);
			if (references == null) {
				continue;
			}
			String clearedComment = null;
			for (PostNumber reference : references) {
				TrackedPost trackedPost = trackedByNumber.get(reference);
				if (trackedPost == null || post.number.equals(trackedPost.postNumber)) {
					continue;
				}
				if (trackedPost.repliesClearedThrough != null
						&& post.number.compareTo(trackedPost.repliesClearedThrough) <= 0) {
					continue;
				}
				HashMap<PostNumber, Reply> knownReplies = knownRepliesByTrackedPost.get(trackedPost);
				Reply knownReply = knownReplies.get(post.number);
				if (knownReply == null) {
					if (clearedComment == null) {
						clearedComment = HtmlParser.clear(post.comment);
					}
					Reply reply = new Reply(post.number, clearedComment, post.timestamp, true);
					trackedPost.replies.add(reply);
					knownReplies.put(post.number, reply);
					changed = true;
				} else if (StringUtils.isEmpty(knownReply.comment)) {
					if (clearedComment == null) {
						clearedComment = HtmlParser.clear(post.comment);
					}
					if (!StringUtils.isEmpty(clearedComment)) {
						knownReply.comment = clearedComment;
						changed = true;
					}
				}
			}
		}
		for (TrackedPost trackedPost : tracked) {
			Collections.sort(trackedPost.replies,
					Comparator.comparing(reply -> reply.postNumber));
		}
		if (changed) {
			serialize();
			notifyChanged();
		}
	}
}
