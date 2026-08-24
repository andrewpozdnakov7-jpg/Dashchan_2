package com.mishiranu.dashchan.content.storage;

import chan.content.Chan;
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
import java.util.List;
import java.util.Objects;

public class MyPostsStorage extends StorageManager.Storage<List<MyPostsStorage.TrackedPost>> {
	private static final String KEY_DATA = "data";
	private static final String KEY_CHAN_NAME = "chanName";
	private static final String KEY_BOARD_NAME = "boardName";
	private static final String KEY_THREAD_NUMBER = "threadNumber";
	private static final String KEY_POST_NUMBER = "postNumber";
	private static final String KEY_COMMENT = "comment";
	private static final String KEY_TIME = "time";
	private static final String KEY_LAST_CHECKED = "lastChecked";
	private static final String KEY_THREAD_DELETED = "threadDeleted";
	private static final String KEY_REPLIES = "replies";
	private static final String KEY_UNREAD = "unread";

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
		public final String comment;
		public final long time;
		public boolean unread;

		private Reply(PostNumber postNumber, String comment, long time, boolean unread) {
			this.postNumber = postNumber;
			this.comment = comment;
			this.time = time;
			this.unread = unread;
		}

		private Reply(Reply reply) {
			this(reply.postNumber, reply.comment, reply.time, reply.unread);
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
			this(trackedPost.chanName, trackedPost.boardName, trackedPost.threadNumber,
					trackedPost.postNumber, trackedPost.comment, trackedPost.time);
			lastChecked = trackedPost.lastChecked;
			threadDeleted = trackedPost.threadDeleted;
			for (Reply reply : trackedPost.replies) {
				replies.add(new Reply(reply));
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

	private final HashMap<String, TrackedPost> postsMap = new HashMap<>();
	private final ArrayList<TrackedPost> posts = new ArrayList<>();
	private final WeakObservable<Runnable> observable = new WeakObservable<>();

	private MyPostsStorage() {
		super("my-posts", 1000, 5000);
		startRead();
	}

	private static String makeKey(String chanName, String boardName, String threadNumber,
			PostNumber postNumber) {
		return chanName + "/" + StringUtils.emptyIfNull(boardName) + "/" + threadNumber + "/" + postNumber;
	}

	public WeakObservable<Runnable> getObservable() {
		return observable;
	}

	private void notifyChanged() {
		ConcurrentUtils.HANDLER.post(() -> {
			for (Runnable runnable : observable) {
				runnable.run();
			}
		});
	}

	private void sort() {
		Collections.sort(posts, Comparator.comparingLong((TrackedPost post) -> post.time).reversed());
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
							case KEY_REPLIES:
								reader.startArray();
								while (!reader.endStruct()) {
									PostNumber replyNumber = null;
									String replyComment = null;
									long replyTime = 0L;
									boolean unread = false;
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
											default:
												reader.skip();
												break;
										}
									}
									if (replyNumber != null) {
										replies.add(new Reply(replyNumber, replyComment, replyTime, unread));
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
		HashSet<ThreadKey> keys = new HashSet<>();
		for (TrackedPost post : posts) {
			if (includeDeleted || !post.threadDeleted) {
				keys.add(post.getThreadKey());
			}
		}
		return new ArrayList<>(keys);
	}

	public synchronized boolean hasThread(ThreadKey key) {
		for (TrackedPost post : posts) {
			if (post.getThreadKey().equals(key)) {
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
			serialize();
			notifyChanged();
		} else if (StringUtils.isEmpty(existing.comment) && !StringUtils.isEmpty(comment)) {
			existing.comment = comment;
			serialize();
			notifyChanged();
		}
	}

	public synchronized boolean addReply(String chanName, String boardName, String threadNumber,
			PostNumber trackedPostNumber, PostNumber replyPostNumber, String comment, long time) {
		Objects.requireNonNull(chanName);
		Objects.requireNonNull(threadNumber);
		Objects.requireNonNull(trackedPostNumber);
		Objects.requireNonNull(replyPostNumber);
		TrackedPost trackedPost = postsMap.get(makeKey(chanName, boardName, threadNumber, trackedPostNumber));
		if (trackedPost == null) {
			return false;
		}
		for (Reply reply : trackedPost.replies) {
			if (reply.postNumber.equals(replyPostNumber)) {
				return false;
			}
		}
		trackedPost.replies.add(new Reply(replyPostNumber, StringUtils.nullIfEmpty(comment),
				time > 0L ? time : System.currentTimeMillis(), true));
		Collections.sort(trackedPost.replies, Comparator.comparing(reply -> reply.postNumber));
		serialize();
		notifyChanged();
		return true;
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
		for (TrackedPost post : posts) {
			if (post.getThreadKey().equals(key)) {
				tracked.add(post);
			}
		}
		if (tracked.isEmpty()) {
			return;
		}
		PostNumber originalPostNumber = null;
		for (Post post : threadPosts) {
			if (originalPostNumber == null || post.number.compareTo(originalPostNumber) < 0) {
				originalPostNumber = post.number;
			}
		}
		Chan chan = Chan.get(key.chanName);
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (TrackedPost trackedPost : tracked) {
			if (trackedPost.threadDeleted) {
				trackedPost.threadDeleted = false;
				changed = true;
			}
			trackedPost.lastChecked = now;
			HashSet<PostNumber> knownReplies = new HashSet<>();
			for (Reply reply : trackedPost.replies) {
				knownReplies.add(reply.postNumber);
			}
			for (Post post : threadPosts) {
				if (post.number.equals(trackedPost.postNumber)) {
					if (StringUtils.isEmpty(trackedPost.comment)) {
						String comment = HtmlParser.clear(post.comment);
						if (!StringUtils.isEmpty(comment)) {
							trackedPost.comment = comment;
							changed = true;
						}
					}
					continue;
				}
				PostItem postItem = PostItem.createPost(post, chan, key.boardName,
						key.threadNumber, originalPostNumber);
				if (postItem.getReferencesTo().contains(trackedPost.postNumber)
						&& knownReplies.add(post.number)) {
					trackedPost.replies.add(new Reply(post.number, HtmlParser.clear(post.comment),
							post.timestamp, true));
					changed = true;
				}
			}
			Collections.sort(trackedPost.replies,
					Comparator.comparing(reply -> reply.postNumber));
		}
		if (changed) {
			serialize();
			notifyChanged();
		}
	}
}
