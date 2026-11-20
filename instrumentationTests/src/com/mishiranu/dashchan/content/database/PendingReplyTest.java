package com.mishiranu.dashchan.content.database;

import static org.junit.Assert.*;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.model.PendingReply;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PendingReplyTest {
	private static final long T = 1_000_000L;
	private static final PagesDatabase.ThreadKey KEY = new PagesDatabase.ThreadKey("test", "b", "100");

	private static PostNumber n(int id) { return new PostNumber(id, 0); }

	private static Post post(int id, String body, long time, Post.Attachment... files) {
		Post.Builder b = new Post.Builder();
		b.number = n(id);
		b.comment = body;
		b.timestamp = time;
		b.attachments = Arrays.asList(files);
		return b.build(false);
	}

	private static PendingReply reply(long id, String text, long time) {
		return new PendingReply(id, n(200), time, time + 500, PendingReply.outgoing(text, null));
	}

	private static Map<PendingReply, Post> match(List<PendingReply> pending, Post... posts) {
		return PendingReply.match(pending, Arrays.asList(posts), n(100), Collections.emptySet());
	}

	@Test public void twoAcknowledgementsNeedTwoDistinctNumbers() {
		PendingReply a = reply(1, "тест", T), b = reply(2, "тест", T);
		List<PendingReply> pending = Arrays.asList(a, b);
		Post first = post(201, "тест", T), second = post(202, "тест", T);
		assertTrue(match(pending, first).isEmpty());
		Map<PendingReply, Post> assigned = match(pending, first, second);
		assertEquals(2, assigned.size());
		assertEquals(first, assigned.get(a));
		assertEquals(second, assigned.get(b));
		assertTrue(match(pending, first, second, post(203, "тест", T)).isEmpty());
		assertTrue(match(Collections.singletonList(a), first, second).isEmpty());
	}

	@Test public void watermarkAndTimeExcludeOldPostsAndKeepQuoteTargets() {
		PendingReply r = reply(1, ">>50 тест", T);
		List<PendingReply> pending = Collections.singletonList(r);
		assertEquals(n(200), PendingReply.cacheAfter(pending, n(205)));
		assertEquals(n(190), PendingReply.cacheAfter(pending, n(190)));
		assertTrue(match(pending, post(180, "&gt;&gt;50 тест", T), post(200, "&gt;&gt;50 тест", T)).isEmpty());
		assertTrue(match(pending, post(201, "&gt;&gt;51 тест", T), post(202, "&gt;&gt;50 тест", T-90_000)).isEmpty());
		Post hit = post(203, "&gt;&gt;50 тест", T);
		assertEquals(hit, match(pending, hit).get(r));
		assertTrue(PendingReply.match(pending, Collections.singletonList(hit), n(100),
				Collections.singleton(hit.number)).isEmpty());
	}

	@Test public void firstLoadExcludesOpAndMissingServerTime() {
		PendingReply r = new PendingReply(1, null, T, T, PendingReply.outgoing("ок", null));
		List<PendingReply> pending = Collections.singletonList(r);
		assertNull(PendingReply.cacheAfter(pending, n(200)));
		assertTrue(match(pending, post(100, "ок", T), post(101, "ок", 0)).isEmpty());
	}

	@Test public void fileOnlyUsesTransmittedFileMetadata() throws Exception {
		File tmp = File.createTempFile("receipt", ".txt",
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
		try {
			try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(new byte[] {1, 2, 3}); }
			ChanPerformer.SendPostData.Attachment upload = new ChanPerformer.SendPostData.Attachment(
					FileHolder.obtain(tmp), "sample.txt", null, false, false, false, false, null);
			String sig = PendingReply.outgoing("", new ChanPerformer.SendPostData.Attachment[] {upload});
			assertNotNull(sig);
			assertNull(PendingReply.outgoing(" ", null));
			PendingReply r = new PendingReply(1, n(200), T, T, sig);
			List<PendingReply> pending = Collections.singletonList(r);
			Uri uri = Uri.parse("https://example.invalid/x");
			Post hit = post(205, "", T, Post.Attachment.File.createExternal(uri, null, "sample.txt", 3, 0, 0, false));
			assertEquals(hit, match(pending, post(201, "🙂", T), post(202, "", T),
					post(203, "", T, Post.Attachment.File.createExternal(uri, null, "sample.txt", 4, 0, 0, false)),
					post(204, "", T, Post.Attachment.File.createExternal(uri, null, "", 3, 0, 0, false)), hit).get(r));
			assertTrue(match(pending, hit, post(206, "", T, hit.attachments.get(0))).isEmpty());
		} finally { tmp.delete(); }
	}

	@Test public void acknowledgedReplySurvivesReopenAndCompetingReaderConsumesOnce() throws Exception {
		File file = File.createTempFile("pending", ".db",
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
		long now = System.currentTimeMillis();
		String sig = PendingReply.outgoing("тест", null);
		try {
			try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
				new PostsDatabase(null).create(db);
				PendingRepliesDatabase.open(db);
				PendingRepliesDatabase.insert(db, KEY, n(200), now, now, sig);
			}
			try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
				PendingRepliesDatabase.open(db);
				List<PendingReply> one = PendingRepliesDatabase.list(db, KEY);
				assertEquals(1, one.size());
				assertEquals(n(200), one.get(0).after());
				Post a = post(201, "тест", now), b = post(202, "тест", now);
				PendingRepliesDatabase.insert(db, KEY, n(200), now, now, sig);
				assertTrue(PendingRepliesDatabase.claim(db, KEY, one, match(one, a)).isEmpty());
				List<PendingReply> both = PendingRepliesDatabase.list(db, KEY);
				Map<PendingReply, Post> matches = match(both, a, b);
				db.execSQL("INSERT INTO posts VALUES('test','b','100',201,0,1,1)");
				assertEquals(2, PendingRepliesDatabase.claim(db, KEY, both, matches).size());
				assertTrue(PendingRepliesDatabase.claim(db, KEY, both, matches).isEmpty());
				assertTrue(PendingRepliesDatabase.list(db, KEY).isEmpty());
				assertEquals(5L, DatabaseUtils.longForQuery(db, "SELECT flags FROM posts WHERE post_number_major=201", null));
			}
		} finally { SQLiteDatabase.deleteDatabase(file); }
	}

	@Test public void failedClaimRollsBackEarlierNumberAndBothReceipts() {
		try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
			new PostsDatabase(null).create(db);
			PendingRepliesDatabase.open(db);
			long now = System.currentTimeMillis();
			for (String text : Arrays.asList("a", "b")) {
				PendingRepliesDatabase.insert(db, KEY, n(200), now, now, PendingReply.outgoing(text, null));
			}
			List<PendingReply> both = PendingRepliesDatabase.list(db, KEY);
			Map<PendingReply, Post> matches = new LinkedHashMap<>();
			matches.put(both.get(0), post(201, "a", now));
			matches.put(both.get(1), post(202, "b", now));
			db.execSQL("INSERT INTO posts VALUES('test','b','100',202,0,1,4)");
			assertTrue(PendingRepliesDatabase.claim(db, KEY, both, matches).isEmpty());
			assertEquals(2, PendingRepliesDatabase.list(db, KEY).size());
			assertEquals(1L, DatabaseUtils.queryNumEntries(db, "posts"));
		}
	}
}
