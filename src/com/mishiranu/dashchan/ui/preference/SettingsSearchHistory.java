package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Completed settings searches only. Separate from exportable application preferences. */
final class SettingsSearchHistory {
	static final int LIMIT = 15;
	static final int MAX_QUERY_LENGTH = 256;
	static final String PREFERENCES_NAME = "settings-search-history";
	interface Store {
		List<String> read();
		void write(List<String> queries);
	}
	private final Store store;

	SettingsSearchHistory(Store store) { this.store = store; }

	static SettingsSearchHistory create(Context context) {
		SharedPreferences preferences = context.getApplicationContext()
				.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
		return new SettingsSearchHistory(new Store() {
			@Override public List<String> read() {
				ArrayList<String> queries = new ArrayList<>();
				Map<String, ?> values = preferences.getAll();
				for (int i = 0; i < LIMIT; i++) {
					Object value = values.get("query." + i);
					if (value instanceof String) queries.add((String) value);
				}
				return queries;
			}
			@Override public void write(List<String> queries) {
				SharedPreferences.Editor editor = preferences.edit().clear();
				for (int i = 0; i < queries.size(); i++) editor.putString("query." + i, queries.get(i));
				editor.apply();
			}
		});
	}

	List<String> getQueries() {
		ArrayList<String> result = new ArrayList<>();
		for (String value : store.read()) {
			String query = clean(value);
			if (query != null && !contains(result, query)) result.add(query);
			if (result.size() == LIMIT) break;
		}
		return result;
	}
	void record(String value) {
		String query = clean(value);
		if (query == null) return;
		List<String> result = getQueries();
		result.removeIf(previous -> same(previous, query));
		result.add(0, query);
		if (result.size() > LIMIT) result.subList(LIMIT, result.size()).clear();
		store.write(result);
	}
	void remove(String query) {
		List<String> result = getQueries();
		result.removeIf(previous -> same(previous, query));
		store.write(result);
	}
	void clear() { store.write(Collections.emptyList()); }
	private static String clean(String value) {
		if (value == null) return null;
		String query = value.trim().replaceAll("\\s+", " ");
		return query.isEmpty() || query.length() > MAX_QUERY_LENGTH ? null : query;
	}
	private static boolean same(String first, String second) {
		return SettingsSearchMatcher.normalize(first).equals(SettingsSearchMatcher.normalize(second));
	}
	private static boolean contains(List<String> queries, String query) {
		for (String previous : queries) if (same(previous, query)) return true;
		return false;
	}
}
