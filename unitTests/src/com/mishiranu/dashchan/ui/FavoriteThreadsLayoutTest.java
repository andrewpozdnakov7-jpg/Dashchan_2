package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FavoriteThreadsLayoutTest {
	private static final class Item {
		final String forum, board, id; final boolean unread;
		Item(String forum, String board, String id, boolean unread) {
			this.forum = forum; this.board = board; this.id = id; this.unread = unread;
		}
	}
	private List<FavoriteThreadsLayout.Group<Item>> arrange(List<Item> source, boolean grouped, boolean unread) {
		return FavoriteThreadsLayout.arrange(source, i -> i.forum, i -> i.board, i -> i.unread, grouped, unread);
	}
	private List<String> ids(List<Item> items) {
		List<String> result = new ArrayList<>(); for (Item item : items) result.add(item.id); return result;
	}
	@Test public void disabledOptionsKeepTheExactOriginalOrderAndDoNotAliasInput() {
		List<Item> source = Arrays.asList(new Item("a", "z", "1", false), new Item("a", "b", "2", true));
		List<FavoriteThreadsLayout.Group<Item>> result = arrange(source, false, false);
		assertEquals(1, result.size()); assertEquals(Arrays.asList("1", "2"), ids(result.get(0).items));
		result.get(0).items.clear(); assertEquals(2, source.size());
	}
	@Test public void unreadPartitionIsStableAndLeavesSourceUntouched() {
		List<Item> source = Arrays.asList(new Item("a", "b", "r1", false), new Item("a", "c", "u1", true),
				new Item("a", "b", "r2", false), new Item("a", "c", "u2", true));
		assertEquals(Arrays.asList("u1", "u2", "r1", "r2"), ids(arrange(source, false, true).get(0).items));
		assertEquals(Arrays.asList("r1", "u1", "r2", "u2"), ids(source));
	}
	@Test public void groupBoardsAlphabeticallyButRetainForumOrderAndOriginalRowsWithinBoard() {
		List<Item> source = Arrays.asList(new Item("second", "z", "1", false), new Item("second", "b", "2", true),
				new Item("first", "b", "3", false), new Item("second", "b", "4", false));
		List<FavoriteThreadsLayout.Group<Item>> groups = arrange(source, true, false);
		assertEquals(3, groups.size()); assertEquals("second", groups.get(0).forum); assertEquals("b", groups.get(0).board);
		assertEquals(Arrays.asList("2", "4"), ids(groups.get(0).items));
		assertEquals("z", groups.get(1).board); assertEquals("first", groups.get(2).forum);
	}
	@Test public void unreadOrderingIsAppliedInsideEachBoardNotAcrossItsHeader() {
		List<Item> source = Arrays.asList(new Item("a", "b", "br", false), new Item("a", "z", "zu", true),
				new Item("a", "b", "bu", true), new Item("a", "z", "zr", false));
		List<FavoriteThreadsLayout.Group<Item>> groups = arrange(source, true, true);
		assertEquals(Arrays.asList("bu", "br"), ids(groups.get(0).items));
		assertEquals(Arrays.asList("zu", "zr"), ids(groups.get(1).items));
	}
	@Test public void sameBoardNameInDifferentForumsAndDelimiterLikeNamesStaySeparate() {
		List<Item> source = Arrays.asList(new Item("a", "b/c", "1", false), new Item("a/b", "c", "2", false),
				new Item("a", "c", "3", false), new Item("a/b", "b/c", "4", false));
		assertEquals(4, arrange(source, true, true).size());
	}
	@Test public void emptyAndNullBoardSnapshotsAreValidWithoutPlaceholderGroups() {
		assertTrue(arrange(Collections.emptyList(), true, true).isEmpty());
		List<Item> source = Arrays.asList(new Item("a", null, "1", false), new Item("a", "", "2", true));
		List<FavoriteThreadsLayout.Group<Item>> groups = arrange(source, true, true);
		assertEquals(2, groups.size()); assertNull(groups.get(0).board); assertEquals("", groups.get(1).board);
	}
	@Test public void disablingOptionsAfterGroupingRestoresOriginalGlobalSequence() {
		List<Item> source = Arrays.asList(new Item("a", "z", "1", false), new Item("a", "b", "2", true), new Item("a", "z", "3", true));
		arrange(source, true, true);
		assertEquals(Arrays.asList("1", "2", "3"), ids(arrange(source, false, false).get(0).items));
	}
	@Test public void inputSnapshotsAndPreviousResultsSurviveLaterUnreadChanges() {
		List<Item> before = Arrays.asList(new Item("a", "b", "1", false), new Item("a", "b", "2", true));
		List<FavoriteThreadsLayout.Group<Item>> first = arrange(before, true, true);
		List<Item> after = Arrays.asList(new Item("a", "b", "1", true), new Item("a", "b", "2", false));
		assertEquals(Arrays.asList("1", "2"), ids(arrange(after, true, true).get(0).items));
		assertEquals(Arrays.asList("2", "1"), ids(first.get(0).items));
	}
}
