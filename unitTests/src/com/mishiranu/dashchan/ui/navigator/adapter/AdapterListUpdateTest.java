package com.mishiranu.dashchan.ui.navigator.adapter;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdapterListUpdateTest {
	private static AdapterListUpdate.Row row(Object key, Object content) {
		return new AdapterListUpdate.Row(key, content);
	}

	private static final class Updates implements ListUpdateCallback {
		int inserted, removed, changed, moved;
		@Override public void onInserted(int position, int count) { inserted += count; }
		@Override public void onRemoved(int position, int count) { removed += count; }
		@Override public void onChanged(int position, int count, Object payload) { changed += count; }
		@Override public void onMoved(int fromPosition, int toPosition) { moved++; }
	}

	private static Updates updates(List<AdapterListUpdate.Row> before, List<AdapterListUpdate.Row> after) {
		DiffUtil.DiffResult result = AdapterListUpdate.calculate(before, after, false);
		assertNotNull(result);
		Updates updates = new Updates();
		result.dispatchUpdatesTo(updates);
		return updates;
	}

	@Test public void identicalSnapshotDoesNotRebind() {
		Updates result = updates(List.of(row("tabs", "replies"), row("reply", "text")),
				List.of(row("tabs", "replies"), row("reply", "text")));
		assertEquals(0, result.changed + result.inserted + result.removed + result.moved);
	}

	@Test public void markingReadOnlyChangesReplyNotHeader() {
		Updates result = updates(List.of(row("tabs", "replies"), row("reply", List.of("text", true))),
				List.of(row("tabs", "replies"), row("reply", List.of("text", false))));
		assertEquals(1, result.changed);
		assertEquals(0, result.inserted + result.removed);
	}

	@Test public void emptyRowIsReplacedByPostAndFooter() {
		Updates result = updates(List.of(row("tabs", "posts"), row("empty", "none")),
				List.of(row("tabs", "posts"), row("post", "text"), row("more", null)));
		assertEquals(1, result.removed);
		assertEquals(2, result.inserted);
		assertEquals(0, result.changed);
	}

	@Test public void appendBeforeFooterDoesNotTouchExistingPost() {
		Updates result = updates(List.of(row("post1", "a"), row("more", null)),
				List.of(row("post1", "a"), row("post2", "b"), row("more", null)));
		assertEquals(1, result.inserted);
		assertEquals(0, result.changed + result.removed);
	}

	@Test public void repliesOnDifferentBoardsAreNotTheSameRow() {
		Updates result = updates(List.of(row(AdapterListUpdate.values("forum", "a", "123"), "same")),
				List.of(row(AdapterListUpdate.values("forum", "b", "123"), "same")));
		assertEquals(1, result.inserted);
		assertEquals(1, result.removed);
	}

	@Test public void changedReplyTargetDoesNotDuplicateReply() {
		Object key = AdapterListUpdate.values("reply", "forum", "board", "thread", "123");
		Updates result = updates(List.of(row(key, List.of("text", "target1"))),
				List.of(row(key, List.of("text", "target2"))));
		assertEquals(1, result.changed);
		assertEquals(0, result.inserted + result.removed);
	}

	@Test public void catalogReplacementCanForceRetainedRowsToRebind() {
		List<AdapterListUpdate.Row> rows = List.of(row("thread", new Object()));
		Updates result = new Updates();
		AdapterListUpdate.calculate(rows, rows, true).dispatchUpdatesTo(result);
		assertEquals(1, result.changed);
	}

	@Test public void insertionAndRemovalKeepSurvivingPositionsMappable() {
		DiffUtil.DiffResult diff = AdapterListUpdate.calculate(
				List.of(row("a", 1), row("b", 2), row("c", 3)),
				List.of(row("b", 2), row("d", 4), row("c", 3)), false);
		assertEquals(-1, diff.convertOldPositionToNew(0));
		assertEquals(0, diff.convertOldPositionToNew(1));
		assertEquals(2, diff.convertOldPositionToNew(2));
	}

	@Test public void sortChangePreservesCorrectIdentityMapping() {
		DiffUtil.DiffResult diff = AdapterListUpdate.calculate(
				List.of(row("a", 1), row("b", 2), row("c", 3)),
				List.of(row("c", 3), row("b", 2), row("a", 1)), false);
		String[] before = {"a", "b", "c"}, after = {"c", "b", "a"};
		for (int i = 0; i < before.length; i++) {
			int mapped = diff.convertOldPositionToNew(i);
			if (mapped >= 0) assertEquals(before[i], after[mapped]);
		}
	}

	@Test public void duplicatesUseFullRefreshInsteadOfAmbiguousMatching() {
		List<AdapterListUpdate.Row> duplicates = List.of(row("same", 1), row("same", 2));
		assertNull(AdapterListUpdate.calculate(duplicates, List.of(), false));
		assertNull(AdapterListUpdate.calculate(List.of(), duplicates, false));
	}

	@Test public void largeDiffIsBounded() {
		ArrayList<AdapterListUpdate.Row> rows = new ArrayList<>();
		for (int i = 0; i <= AdapterListUpdate.MAX_DIFF_ROWS; i++) rows.add(row(i, i));
		assertNull(AdapterListUpdate.calculate(rows, List.of(), false));
		assertNull(AdapterListUpdate.calculate(List.of(), rows, false));
	}
}
