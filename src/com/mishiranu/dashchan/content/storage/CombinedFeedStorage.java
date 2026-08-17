package com.mishiranu.dashchan.content.storage;

import chan.util.StringUtils;
import com.mishiranu.dashchan.util.WeakObservable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class CombinedFeedStorage extends StorageManager.JsonOrgStorage<List<CombinedFeedStorage.Feed>> {
	private static final String KEY_FEEDS = "feeds";
	public static final int MAX_FEEDS = 20;

	public interface Observer {
		void onCombinedFeedsChanged();
	}

	public static class Source {
		public String chanName;
		public String boardName;

		public Source() {}

		public Source(String chanName, String boardName) {
			this.chanName = chanName;
			this.boardName = boardName;
		}

		public Source(Source source) {
			this(source.chanName, source.boardName);
		}

		private JSONObject serialize() throws JSONException {
			return new JSONObject().put("chanName", chanName).put("boardName", boardName);
		}

		private static Source deserialize(JSONObject json) {
			return new Source(json.optString("chanName"), json.optString("boardName"));
		}
	}

	public static class Feed {
		public String id;
		public String title;
		public boolean showSticky = true;
		public boolean showBoard = true;
		public final ArrayList<Source> sources = new ArrayList<>();

		public Feed() {}

		public Feed(Feed feed) {
			id = feed.id;
			title = feed.title;
			showSticky = feed.showSticky;
			showBoard = feed.showBoard;
			for (Source source : feed.sources) {
				sources.add(new Source(source));
			}
		}

		public String getPrimaryChanName() {
			return sources.isEmpty() ? null : sources.get(0).chanName;
		}

		public boolean containsChan(String chanName) {
			for (Source source : sources) {
				if (source.chanName.equals(chanName)) {
					return true;
				}
			}
			return false;
		}

		private JSONObject serialize() throws JSONException {
			JSONArray array = new JSONArray();
			for (Source source : sources) {
				array.put(source.serialize());
			}
			return new JSONObject().put("id", id).put("title", title)
					.put("showSticky", showSticky).put("showBoard", showBoard).put("sources", array);
		}

		private static Feed deserialize(JSONObject json) {
			Feed feed = new Feed();
			feed.id = json.optString("id");
			feed.title = json.optString("title");
			feed.showSticky = json.optBoolean("showSticky", true);
			feed.showBoard = json.optBoolean("showBoard", true);
			JSONArray array = json.optJSONArray("sources");
			if (array != null) {
				for (int i = 0; i < array.length(); i++) {
					JSONObject sourceJson = array.optJSONObject(i);
					if (sourceJson != null) {
						Source source = Source.deserialize(sourceJson);
						if (!StringUtils.isEmpty(source.chanName) && !StringUtils.isEmpty(source.boardName)) {
							feed.sources.add(source);
						}
					}
				}
			}
			return feed;
		}
	}

	private static final CombinedFeedStorage INSTANCE = new CombinedFeedStorage();

	public static CombinedFeedStorage getInstance() {
		return INSTANCE;
	}

	private final LinkedHashMap<String, Feed> feeds = new LinkedHashMap<>();
	private final WeakObservable<Observer> observable = new WeakObservable<>();

	private CombinedFeedStorage() {
		super("combined_feeds", 500, 5000);
		startRead();
	}

	public WeakObservable<Observer> getObservable() {
		return observable;
	}

	@Override
	public synchronized List<Feed> onClone() {
		ArrayList<Feed> result = new ArrayList<>(feeds.size());
		for (Feed feed : feeds.values()) {
			result.add(new Feed(feed));
		}
		return result;
	}

	@Override
	public synchronized void onDeserialize(JSONObject jsonObject) {
		JSONArray array = jsonObject.optJSONArray(KEY_FEEDS);
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length() && feeds.size() < MAX_FEEDS; i++) {
			JSONObject json = array.optJSONObject(i);
			if (json != null) {
				Feed feed = Feed.deserialize(json);
				if (!StringUtils.isEmpty(feed.id) && isValid(feed)) {
					feeds.put(feed.id, feed);
				}
			}
		}
	}

	@Override
	public JSONObject onSerialize(List<Feed> feeds) throws JSONException {
		JSONArray array = new JSONArray();
		for (Feed feed : feeds) {
			array.put(feed.serialize());
		}
		return new JSONObject().put(KEY_FEEDS, array);
	}

	public synchronized List<Feed> getFeeds() {
		return onClone();
	}

	public synchronized Feed getFeed(String id) {
		Feed feed = feeds.get(id);
		return feed != null ? new Feed(feed) : null;
	}

	public synchronized boolean put(Feed feed) {
		if (!isValid(feed)) {
			return false;
		}
		if (StringUtils.isEmpty(feed.id)) {
			if (feeds.size() >= MAX_FEEDS) {
				return false;
			}
			feed.id = UUID.randomUUID().toString();
		} else if (!feeds.containsKey(feed.id) && feeds.size() >= MAX_FEEDS) {
			return false;
		}
		feeds.put(feed.id, new Feed(feed));
		serialize();
		notifyChanged();
		return true;
	}

	private static boolean isValid(Feed feed) {
		if (StringUtils.isEmptyOrWhitespace(feed.title) || feed.sources.size() < 2) {
			return false;
		}
		ArrayList<String> sourceKeys = new ArrayList<>();
		for (Source source : feed.sources) {
			if (StringUtils.isEmpty(source.chanName) || StringUtils.isEmpty(source.boardName)) {
				return false;
			}
			String key = source.chanName + '\n' + source.boardName;
			if (sourceKeys.contains(key)) {
				return false;
			}
			sourceKeys.add(key);
		}
		return true;
	}

	public synchronized void remove(String id) {
		if (feeds.remove(id) != null) {
			serialize();
			notifyChanged();
		}
	}

	private void notifyChanged() {
		for (Observer observer : observable) {
			observer.onCombinedFeedsChanged();
		}
	}
}
