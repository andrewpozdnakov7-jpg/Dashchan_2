package com.mishiranu.dashchan.content.storage;

import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local bookmarks, independent of Reddit cookies, subscriptions and recent pages. */
public final class RedditCommunities {
	private static final String KEY = "reddit_custom_communities_v1";
	public static final String UNGROUPED = "ungrouped";

	private RedditCommunities() {}

	public static final class Category {
		public final String id;
		public String title;
		public final ArrayList<String> communities = new ArrayList<>();

		public Category(String title) {
			this(UUID.randomUUID().toString(), title);
		}

		private Category(String id, String title) {
			this.id = id;
			this.title = title;
		}
	}

	/** Accept a name, r/name, /r/name/ or a community URL, never an arbitrary URL. */
	public static String normalize(String input) {
		if (input == null) return null;
		String name = input.trim();
		if (name.contains("://")) {
			try {
				URI uri = new URI(name);
				String host = uri.getHost();
				if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
						|| host == null || uri.getUserInfo() != null || uri.getPort() != -1) return null;
				host = host.toLowerCase(Locale.US);
				if (!(host.equals("reddit.com") || host.equals("www.reddit.com")
						|| host.equals("old.reddit.com") || host.equals("new.reddit.com"))) return null;
				name = uri.getPath();
				if (name == null || !name.startsWith("/r/")) return null;
			} catch (java.net.URISyntaxException e) {
				return null;
			}
		}
		name = name.replaceFirst("(?i)^/?r/", "").replaceFirst("/$", "");
		if (!name.matches("[A-Za-z0-9_]{2,64}") || name.equalsIgnoreCase("all")
				|| name.equalsIgnoreCase("popular")) return null;
		return name;
	}

	public static ArrayList<Category> load() {
		ArrayList<Category> result = new ArrayList<>();
		result.add(new Category(UNGROUPED, ""));
		HashSet<String> ids = new HashSet<>();
		HashSet<String> names = new HashSet<>();
		try {
			JSONArray array = new JSONArray(Preferences.PREFERENCES.getString(KEY, "[]"));
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object == null) continue;
				String id = object.optString("id");
				if (id.isEmpty() || !ids.add(id)) continue;
				Category category = UNGROUPED.equals(id) ? result.get(0)
						: new Category(id, object.optString("title"));
				if (!UNGROUPED.equals(id)) result.add(category);
				JSONArray entries = object.optJSONArray("communities");
				if (entries == null) continue;
				for (int j = 0; j < entries.length(); j++) {
					String name = normalize(entries.optString(j));
					if (name != null && names.add(name.toLowerCase(Locale.US))) category.communities.add(name);
				}
			}
		} catch (JSONException e) {
			// A malformed preference must not crash navigation.
		}
		return result;
	}

	public static void save(List<Category> categories) {
		JSONArray array = new JSONArray();
		try {
			for (Category category : categories) {
				array.put(new JSONObject().put("id", category.id).put("title", category.title)
						.put("communities", new JSONArray(category.communities)));
			}
		} catch (JSONException e) {
			throw new IllegalStateException(e);
		}
		try (SharedPreferences.Editor editor = Preferences.PREFERENCES.edit()) {
			editor.put(KEY, array.toString());
		}
	}
}
