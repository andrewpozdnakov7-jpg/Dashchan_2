package com.mishiranu.dashchan.content.database;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.database.HistoryDatabase.Board;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Production selection predicates against an isolated SQLite database; no user history is touched. */
@RunWith(AndroidJUnit4.class)
public class HistorySectionFilterTest {
	private SQLiteDatabase database() {
		SQLiteDatabase db = SQLiteDatabase.create(null);
		new HistoryDatabase(null).create(db);
		return db;
	}

	private void insert(SQLiteDatabase db, String site, String board, String thread, String title, long time) {
		ContentValues values = new ContentValues();
		values.put("chan_name", site);
		values.put("board_name", board);
		values.put("thread_number", thread);
		values.put("title", title);
		values.put("time", time);
		db.insertOrThrow("history", null, values);
	}

	private List<String> find(SQLiteDatabase db, String site, String query, Collection<Board> boards) {
		Expression.Filter filter = HistoryDatabase.buildHistoryFilter(site, query, boards);
		ArrayList<String> result = new ArrayList<>();
		try (Cursor cursor = db.query("history", new String[]{"thread_number"}, filter.value,
				filter.args, null, null, "time DESC")) {
			while (cursor.moveToNext()) result.add(cursor.getString(0));
		}
		return result;
	}

	@Test public void sameSectionCodeOnDifferentSitesDoesNotLeakIntoSelection() {
		try (SQLiteDatabase db = database()) {
			insert(db, "one", "b", "1", "first", 1);
			insert(db, "two", "b", "2", "second", 2);
			assertEquals(Collections.singletonList("1"), find(db, null, null,
					Collections.singletonList(new Board("one", "b"))));
		}
	}

	@Test public void multipleSectionsCombineWithTextAndOuterSiteScope() {
		try (SQLiteDatabase db = database()) {
			insert(db, "one", "mobi", "1", "phone", 1);
			insert(db, "one", "tech", "2", "phone news", 2);
			insert(db, "one", "b", "3", "phone", 3);
			insert(db, "two", "mobi", "4", "phone", 4);
			insert(db, "one", "tech", "5", "unrelated", 5);
			List<Board> selected = Arrays.asList(new Board("one", "mobi"), new Board("one", "tech"),
					new Board("two", "mobi"));
			assertEquals(Arrays.asList("4", "2", "1"), find(db, null, "phone", selected));
			assertEquals(Arrays.asList("2", "1"), find(db, "one", "phone", selected));
		}
	}

	@Test public void emptySelectionRestoresAllSectionsInNewestFirstOrder() {
		try (SQLiteDatabase db = database()) {
			insert(db, "one", "b", "1", "first", 1);
			insert(db, "two", "tech", "2", "second", 2);
			assertEquals(Arrays.asList("2", "1"), find(db, null, "", Collections.emptyList()));
			assertEquals(Collections.singletonList("1"), find(db, "one", null, Collections.emptyList()));
			assertTrue(find(db, null, null, Collections.singletonList(new Board("one", "gone"))).isEmpty());
		}
	}

	@Test public void thousandsOfSelectionsAndSqlMetacharactersRemainExact() {
		try (SQLiteDatabase db = database()) {
			String special = "x') OR 1=1 --";
			insert(db, "site'one", special, "1", "title", 1);
			insert(db, "site'one", "other", "2", "title", 2);
			ArrayList<Board> selected = new ArrayList<>();
			for (int i = 0; i < 2000; i++) selected.add(new Board("site'one", "board" + i));
			selected.add(new Board("site'one", special));
			assertEquals(Collections.singletonList("1"), find(db, null, null, selected));
		}
	}

	@Test public void sectionIdentityNormalizesAnAbsentBoardButKeepsSitesDistinct() {
		assertEquals(new Board("one", ""), new Board("one", null));
		assertEquals(new Board("one", "b").hashCode(), new Board("one", "b").hashCode());
		assertNotEquals(new Board("one", "b"), new Board("two", "b"));
	}
}
