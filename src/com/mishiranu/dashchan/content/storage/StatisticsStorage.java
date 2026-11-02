package com.mishiranu.dashchan.content.storage;

import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.text.JsonSerial;
import chan.text.ParseException;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;

public class StatisticsStorage extends StorageManager.Storage<Map<String, StatisticsStorage.StatisticsItem>> {
	private static final String KEY_START_TIME = "startTime";
	private static final String KEY_DATA = "data";
	private static final String KEY_CHAN_NAME = "chanName";
	private static final String KEY_THREADS_VIEWED = "threadsViewed";
	private static final String KEY_POSTS_SENT = "postsSent";
	private static final String KEY_THREADS_CREATED = "threadsCreated";
	private static final String KEY_REPLIES_RECEIVED = "repliesReceived";
	private static final String KEY_REPLIES_SINCE = "repliesSince";
	private static final String KEY_REPLIES_SEEN = "repliesSeen";

	private static final StatisticsStorage INSTANCE = new StatisticsStorage();

	public static StatisticsStorage getInstance() {
		return INSTANCE;
	}

	private final HashMap<String, StatisticsItem> statisticsItems = new HashMap<>();
	private long startTime;
	private final ReplyStatisticsLedger replies = new ReplyStatisticsLedger(System.currentTimeMillis());
	private boolean repliesStartTimeLoaded;

	private StatisticsStorage() {
		super("statistics", 1000, 10000);
		startRead();
		if (!repliesStartTimeLoaded) serialize(); // Persist the date on first use/migration, not every launch.
	}

	@Override
	public synchronized Map<String, StatisticsItem> onClone() {
		Snapshot result = new Snapshot(startTime, replies.copy());
		copyItems(result);
		return result;
	}
	private void copyItems(Map<String, StatisticsItem> result) {
		for (Map.Entry<String, StatisticsItem> entry : statisticsItems.entrySet()) {
			StatisticsItem item = entry.getValue();
			result.put(entry.getKey(), new StatisticsItem(item.threadsViewed, item.postsSent, item.threadsCreated));
		}
		for (Map.Entry<String, Long> entry : replies.counts.entrySet()) {
			StatisticsItem item = result.get(entry.getKey());
			if (item == null) { item = new StatisticsItem(0, 0, 0); result.put(entry.getKey(), item); }
			item.repliesReceived = entry.getValue();
		}
	}

	private static final class Snapshot extends HashMap<String, StatisticsItem> {
		private static final long serialVersionUID = 1L;
		final long startTime;
		final ReplyStatisticsLedger replies;
		Snapshot(long startTime, ReplyStatisticsLedger replies) { this.startTime = startTime; this.replies = replies; }
	}

	@Override
	public synchronized void onRead(InputStream input) throws IOException {
		try {
			JsonSerial.Reader reader = JsonSerial.reader(input);
			reader.startObject();
			while (!reader.endStruct()) {
				switch (reader.nextName()) {
					case KEY_DATA: {
						reader.startArray();
						while (!reader.endStruct()) {
							String chanName = null;
							int threadsViewed = 0;
							int postsSent = 0;
							int threadsCreated = 0;
							long repliesReceived = 0;
							reader.startObject();
							while (!reader.endStruct()) {
								switch (reader.nextName()) {
									case KEY_CHAN_NAME: {
										chanName = reader.nextString();
										break;
									}
									case KEY_THREADS_VIEWED: {
										threadsViewed = reader.nextInt();
										break;
									}
									case KEY_POSTS_SENT: {
										postsSent = reader.nextInt();
										break;
									}
									case KEY_THREADS_CREATED: {
										threadsCreated = reader.nextInt();
										break;
									}
									case KEY_REPLIES_RECEIVED: {
										repliesReceived = Math.max(0L, reader.nextLong());
										break;
									}
									default: {
										reader.skip();
										break;
									}
								}
							}
							statisticsItems.put(chanName, new StatisticsItem(threadsViewed,
									postsSent, threadsCreated));
							if (chanName != null && repliesReceived > 0) replies.counts.put(chanName, repliesReceived);
						}
						break;
					}
					case KEY_START_TIME: {
						startTime = Math.max(reader.nextLong(), 0L);
						break;
					}
					case KEY_REPLIES_SINCE: {
						long since = reader.nextLong();
						if (since > 0) { replies.since = since; repliesStartTimeLoaded = true; }
						break;
					}
					case KEY_REPLIES_SEEN: {
						reader.startArray();
						while (!reader.endStruct()) {
							String fingerprint = reader.nextString();
							if (fingerprint != null && fingerprint.matches("[0-9a-f]{64}")) replies.seen.add(fingerprint);
						}
						break;
					}
					default: {
						reader.skip();
						break;
					}
				}
			}
		} catch (ParseException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void onWrite(Map<String, StatisticsItem> statisticsItems, OutputStream output) throws IOException {
		JsonSerial.Writer writer = JsonSerial.writer(output);
		writer.startObject();
		writer.name(KEY_DATA);
		writer.startArray();
		for (HashMap.Entry<String, StatisticsItem> entry : statisticsItems.entrySet()) {
			StatisticsItem statisticsItem = entry.getValue();
			writer.startObject();
			writer.name(KEY_CHAN_NAME);
			writer.value(entry.getKey());
			writer.name(KEY_THREADS_VIEWED);
			writer.value(statisticsItem.threadsViewed);
			writer.name(KEY_POSTS_SENT);
			writer.value(statisticsItem.postsSent);
			writer.name(KEY_THREADS_CREATED);
			writer.value(statisticsItem.threadsCreated);
			writer.name(KEY_REPLIES_RECEIVED);
			writer.value(statisticsItem.repliesReceived);
			writer.endObject();
		}
		writer.endArray();
		writer.name(KEY_START_TIME);
		writer.value(((Snapshot) statisticsItems).startTime);
		ReplyStatisticsLedger replies = ((Snapshot) statisticsItems).replies;
		writer.name(KEY_REPLIES_SINCE); writer.value(replies.since);
		writer.name(KEY_REPLIES_SEEN); writer.startArray();
		for (String fingerprint : replies.seen) writer.value(fingerprint);
		writer.endArray();
		writer.endObject();
		writer.flush();
	}

	public static class StatisticsItem {
		public int threadsViewed;
		public int postsSent;
		public int threadsCreated;
		public long repliesReceived;

		private StatisticsItem(int threadsViewed, int postsSent, int threadsCreated) {
			this.threadsViewed = threadsViewed;
			this.postsSent = postsSent;
			this.threadsCreated = threadsCreated;
		}
	}

	public synchronized HashMap<String, StatisticsItem> getItems() {
		HashMap<String, StatisticsItem> result = new HashMap<>();
		copyItems(result);
		return result;
	}

	public synchronized long getStartTime() {
		return startTime;
	}
	public synchronized long getRepliesStartTime() { return replies.since; }

	/** Shared ingestion for local reads and push; count=false seeds history or excludes known own posts. */
	public synchronized void recordReplies(String chanName, String boardName, String threadNumber,
			Collection<PostNumber> numbers, boolean count) {
		boolean changed = false;
		for (PostNumber number : numbers) {
			changed |= replies.record(chanName, boardName, threadNumber, number.toString(), count);
		}
		if (changed) {
			if (count && startTime <= 0) startTime = System.currentTimeMillis();
			serialize();
		}
	}

	private StatisticsItem obtainStatisticsItem(String chanName) {
		StatisticsItem statisticsItem = statisticsItems.get(chanName);
		if (statisticsItem == null) {
			if (statisticsItems.isEmpty() && replies.counts.isEmpty()) {
				startTime = System.currentTimeMillis();
			}
			statisticsItem = new StatisticsItem(0, 0, 0);
			statisticsItems.put(chanName, statisticsItem);
		}
		return statisticsItem;
	}

	public synchronized void incrementThreadsViewed(String chanName) {
		Chan chan = Chan.get(chanName);
		ChanConfiguration.Statistics statistics = chan.configuration.safe().obtainStatistics();
		if (statistics == null || !statistics.threadsViewed) {
			return;
		}
		StatisticsItem statisticsItem = obtainStatisticsItem(chanName);
		statisticsItem.threadsViewed++;
		serialize();
	}

	public synchronized void incrementPostsSent(String chanName, boolean newThread) {
		Chan chan = Chan.get(chanName);
		ChanConfiguration.Statistics statistics = chan.configuration.safe().obtainStatistics();
		if (statistics == null || !(statistics.postsSent || statistics.threadsCreated && newThread)) {
			return;
		}
		StatisticsItem statisticsItem = obtainStatisticsItem(chanName);
		if (statistics.postsSent) {
			statisticsItem.postsSent++;
		}
		if (statistics.threadsCreated && newThread) {
			statisticsItem.threadsCreated++;
		}
		serialize();
	}

	public synchronized void clear() {
		statisticsItems.clear();
		startTime = 0;
		replies.reset(System.currentTimeMillis());
		serialize();
	}
}
