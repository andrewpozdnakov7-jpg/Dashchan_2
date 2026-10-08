package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SettingsSearchHistoryTest {
	private static final class MemoryStore implements SettingsSearchHistory.Store {
		List<String> values = new ArrayList<>();
		int writes;
		@Override public List<String> read() { return new ArrayList<>(values); }
		@Override public void write(List<String> queries) { writes++; values = new ArrayList<>(queries); }
	}
	@Test public void newestFirstDeduplicatedAndLimitedTo15() {
		MemoryStore store = new MemoryStore();
		SettingsSearchHistory history = new SettingsSearchHistory(store);
		for (int i = 0; i < 20; i++) history.record("запрос " + i);
		assertEquals(15, history.getQueries().size());
		assertEquals("запрос 19", history.getQueries().get(0));
		assertEquals("запрос 5", history.getQueries().get(14));
		history.record("ЗАПРОС 10");
		assertEquals("ЗАПРОС 10", history.getQueries().get(0));
		assertEquals(15, history.getQueries().size());
	}
	@Test public void trimsIgnoresEmptyOrLongInputAndNormalizesYoForIdentity() {
		MemoryStore store = new MemoryStore();
		SettingsSearchHistory history = new SettingsSearchHistory(store);
		history.record(null); history.record("  ");
		history.record(String.join("", java.util.Collections.nCopies(257, "а")));
		assertEquals(0, store.writes);
		history.record("  Тёмная\n  тема ");
		history.record("темная тема");
		assertEquals(Arrays.asList("темная тема"), history.getQueries());
	}
	@Test public void removalClearAndNewInstanceUseTheSameStore() {
		MemoryStore store = new MemoryStore();
		SettingsSearchHistory history = new SettingsSearchHistory(store);
		history.record("галерея"); history.record("прокси");
		SettingsSearchHistory reopened = new SettingsSearchHistory(store);
		assertEquals(Arrays.asList("прокси", "галерея"), reopened.getQueries());
		reopened.remove("ПРОКСИ");
		assertEquals(Arrays.asList("галерея"), history.getQueries());
		reopened.clear(); assertTrue(history.getQueries().isEmpty());
	}
	@Test public void malformedStoredValuesAreFilteredAndReturnedListsAreDetached() {
		MemoryStore store = new MemoryStore();
		store.values = new ArrayList<>(Arrays.asList(null, "", "галерея", " ГАЛЕРЕЯ ", "прокси"));
		SettingsSearchHistory history = new SettingsSearchHistory(store);
		List<String> values = history.getQueries();
		assertEquals(Arrays.asList("галерея", "прокси"), values);
		values.clear(); assertEquals(2, history.getQueries().size());
	}
}
