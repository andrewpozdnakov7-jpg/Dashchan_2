package com.mishiranu.dashchan.content.storage;

import android.net.Uri;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.WeakObservable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores only the minimal navigation metadata needed to restore Reddit pages in the drawer. */
public final class RedditPageStorage extends StorageManager.JsonOrgStorage<List<RedditPageStorage.Entry>> {
	private static final String KEY_PAGES = "pages";
	private static final int MAX_SUBREDDITS = 20;
	private static final int MAX_THREADS = 40;

	public enum Type {SUBREDDIT, THREAD}

	public interface Observer {
		void onRedditPagesChanged();
	}

	public static final class Entry {
		public final Type type;
		public final String url;
		public final String subreddit;
		public final String threadId;
		public final String title;
		public final long timestamp;
		private final boolean titleFromPage;

		private Entry(Type type, String url, String subreddit, String threadId, String title, long timestamp,
				boolean titleFromPage) {
			this.type = type;
			this.url = url;
			this.subreddit = subreddit;
			this.threadId = threadId;
			this.title = title;
			this.timestamp = timestamp;
			this.titleFromPage = titleFromPage;
		}

		private Entry withTitle(String title) {
			return new Entry(type, url, subreddit, threadId, title, timestamp, true);
		}

		private JSONObject serialize() throws JSONException {
			return new JSONObject().put("type", type.name()).put("url", url).put("subreddit", subreddit)
					.put("threadId", threadId).put("title", title).put("timestamp", timestamp)
					.put("titleFromPage", titleFromPage);
		}
	}

	private static final RedditPageStorage INSTANCE = new RedditPageStorage();

	public static RedditPageStorage getInstance() {
		return INSTANCE;
	}

	private final ArrayList<Entry> entries = new ArrayList<>();
	private final WeakObservable<Observer> observable = new WeakObservable<>();

	private RedditPageStorage() {
		super("reddit_pages", 500, 5000);
		startRead();
	}

	public WeakObservable<Observer> getObservable() {
		return observable;
	}

	@Override
	public synchronized List<Entry> onClone() {
		return new ArrayList<>(entries);
	}

	@Override
	public synchronized void onDeserialize(JSONObject jsonObject) {
		JSONArray array = jsonObject.optJSONArray(KEY_PAGES);
		if (array == null) {
			return;
		}
		for (int i = 0; i < array.length(); i++) {
			JSONObject json = array.optJSONObject(i);
			if (json == null) {
				continue;
			}
			Entry parsed = parse(json.optString("url"), json.optString("title"),
					json.optLong("timestamp", System.currentTimeMillis()));
			if (parsed != null && find(parsed.url) < 0) {
				parsed = new Entry(parsed.type, parsed.url, parsed.subreddit, parsed.threadId, parsed.title,
						parsed.timestamp, json.optBoolean("titleFromPage", true));
				entries.add(parsed);
			}
		}
		trim();
	}

	@Override
	public JSONObject onSerialize(List<Entry> entries) throws JSONException {
		JSONArray array = new JSONArray();
		for (Entry entry : entries) {
			array.put(entry.serialize());
		}
		return new JSONObject().put(KEY_PAGES, array);
	}

	public synchronized List<Entry> getPages() {
		return onClone();
	}

	public synchronized Entry getFirstPage() {
		return entries.isEmpty() ? null : entries.get(0);
	}

	public synchronized void record(String url, String title) {
		Entry parsed = parse(url, title, System.currentTimeMillis());
		if (parsed == null) {
			return;
		}
		int index = find(parsed.url);
		if (index >= 0) {
			Entry previous = entries.remove(index);
			if (!parsed.titleFromPage && previous.titleFromPage) {
				parsed = new Entry(parsed.type, parsed.url, parsed.subreddit, parsed.threadId,
						previous.title, parsed.timestamp, true);
			}
		}
		entries.add(0, parsed);
		trim();
		serialize();
		notifyChanged();
	}

	public synchronized void updateTitle(String url, String title) {
		Entry parsed = parse(url, title, 0L);
		if (parsed == null || !parsed.titleFromPage) {
			return;
		}
		int index = find(parsed.url);
		if (index >= 0) {
			Entry previous = entries.get(index);
			if (!parsed.title.equals(previous.title)) {
				entries.set(index, previous.withTitle(parsed.title));
				serialize();
				notifyChanged();
			}
		}
	}

	public synchronized void remove(String url) {
		String normalized = normalizeUrl(url);
		int index = normalized != null ? find(normalized) : -1;
		if (index >= 0) {
			entries.remove(index);
			serialize();
			notifyChanged();
		}
	}

	public synchronized void clear() {
		if (!entries.isEmpty()) {
			entries.clear();
			serialize();
			notifyChanged();
		}
	}

	public static String normalizeUrl(String url) {
		Entry entry = parse(url, null, 0L);
		return entry != null ? entry.url : null;
	}

	public static Entry parse(String url, String title) {
		return parse(url, title, System.currentTimeMillis());
	}

	private static Entry parse(String url, String title, long timestamp) {
		if (StringUtils.isEmptyOrWhitespace(url)) {
			return null;
		}
		Uri uri = Uri.parse(url);
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			return null;
		}
		String host = uri.getHost();
		if (host == null) {
			return null;
		}
		host = host.toLowerCase(Locale.US);
		if (!(host.equals("reddit.com") || host.endsWith(".reddit.com"))) {
			return null;
		}
		List<String> segments = uri.getPathSegments();
		if (segments.size() < 2 || !"r".equalsIgnoreCase(segments.get(0))) {
			return null;
		}
		String subreddit = segments.get(1);
		if (!isSubredditName(subreddit) || "all".equalsIgnoreCase(subreddit)
				|| "popular".equalsIgnoreCase(subreddit)) {
			return null;
		}
		subreddit = subreddit.toLowerCase(Locale.US);
		Uri.Builder builder = new Uri.Builder().scheme("https").authority("www.reddit.com")
				.appendPath("r").appendPath(subreddit);
		if (segments.size() >= 4 && "comments".equalsIgnoreCase(segments.get(2))) {
			String threadId = segments.get(3).toLowerCase(Locale.US);
			if (!isThreadId(threadId)) {
				return null;
			}
			builder.appendPath("comments").appendPath(threadId);
			String slug = segments.size() >= 5 ? segments.get(4) : null;
			String fallback = !StringUtils.isEmptyOrWhitespace(slug)
					? slug.replace('-', ' ').replace('_', ' ') : "/r/" + subreddit + "/ • " + threadId;
			boolean titleFromPage = isUsefulPageTitle(title);
			return new Entry(Type.THREAD, finish(builder), subreddit, threadId,
					cleanTitle(titleFromPage ? title : null, fallback), timestamp, titleFromPage);
		}
		return new Entry(Type.SUBREDDIT, finish(builder), subreddit, null,
				"/r/" + subreddit + "/", timestamp, false);
	}

	private static String finish(Uri.Builder builder) {
		String value = builder.build().toString();
		return value.endsWith("/") ? value : value + "/";
	}

	private static boolean isSubredditName(String value) {
		if (value.length() < 2 || value.length() > 64) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) {
				return false;
			}
		}
		return true;
	}

	private static boolean isThreadId(String value) {
		if (StringUtils.isEmpty(value) || value.length() > 32) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
				return false;
			}
		}
		return true;
	}

	private static String cleanTitle(String title, String fallback) {
		String result = !StringUtils.isEmptyOrWhitespace(title) ? title.trim().replaceAll("\\s+", " ") : fallback;
		String lower = result.toLowerCase(Locale.US);
		for (String suffix : new String[] {" - reddit", " | reddit"}) {
			if (lower.endsWith(suffix)) {
				result = result.substring(0, result.length() - suffix.length()).trim();
				lower = result.toLowerCase(Locale.US);
			}
		}
		int subredditSuffix = lower.lastIndexOf(" : r/");
		if (subredditSuffix > 0) {
			result = result.substring(0, subredditSuffix).trim();
		}
		if (StringUtils.isEmptyOrWhitespace(result) || "reddit".equalsIgnoreCase(result)) {
			result = fallback;
		}
		return result.length() > 160 ? result.substring(0, 160).trim() + '…' : result;
	}

	private static boolean isUsefulPageTitle(String title) {
		if (StringUtils.isEmptyOrWhitespace(title)) {
			return false;
		}
		String lower = title.trim().toLowerCase(Locale.US);
		return !lower.equals("reddit") && !lower.equals("reddit - dive into anything");
	}

	private int find(String url) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).url.equals(url)) {
				return i;
			}
		}
		return -1;
	}

	private void trim() {
		int subreddits = 0;
		int threads = 0;
		for (Iterator<Entry> iterator = entries.iterator(); iterator.hasNext();) {
			Entry entry = iterator.next();
			if (entry.type == Type.SUBREDDIT ? ++subreddits > MAX_SUBREDDITS : ++threads > MAX_THREADS) {
				iterator.remove();
			}
		}
	}

	private void notifyChanged() {
		for (Observer observer : observable) {
			observer.onRedditPagesChanged();
		}
	}
}
