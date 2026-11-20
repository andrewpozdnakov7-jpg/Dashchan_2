package com.mishiranu.dashchan.content.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.mishiranu.dashchan.content.database.PagesDatabase.ThreadKey;
import com.mishiranu.dashchan.content.model.PendingReply;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PendingRepliesDatabase {
	static final String TABLE = "pending_replies";
	static final String INDEX = "pending_replies_thread";
	private static final long RETENTION = 24 * 60 * 60_000L;
	private static final String THREAD = "chan=? AND board=? AND thread=?";

	private PendingRepliesDatabase() {}

	static void open(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS pending_replies (id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "chan TEXT NOT NULL, board TEXT NOT NULL, thread TEXT NOT NULL, after_num TEXT, "
				+ "started INTEGER NOT NULL, accepted INTEGER NOT NULL, signature TEXT NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS pending_replies_thread ON pending_replies(chan,board,thread)");
		prune(db);
	}

	private static void prune(SQLiteDatabase db) {
		db.delete(TABLE, "accepted < ?", new String[] {Long.toString(System.currentTimeMillis() - RETENTION)});
	}

	public static void remember(ThreadKey key, PostNumber after, long started, long accepted, String signature) {
		if (signature == null) return;
		CommonDatabase.getInstance().execute(db -> {
			insert(db, key, after, started, accepted, signature);
			return null;
		});
	}

	static void insert(SQLiteDatabase db, ThreadKey key, PostNumber after, long started, long accepted, String signature) {
		prune(db);
		ContentValues values = new ContentValues();
		values.put("chan", key.chanName);
		values.put("board", key.boardName);
		values.put("thread", key.threadNumber);
		values.put("after_num", after != null ? after.toString() : null);
		values.put("started", started);
		values.put("accepted", accepted);
		values.put("signature", signature);
		db.insertOrThrow(TABLE, null, values);
	}

	public static List<PendingReply> list(ThreadKey key) {
		return CommonDatabase.getInstance().execute(db -> list(db, key));
	}

	static List<PendingReply> list(SQLiteDatabase db, ThreadKey key) {
		ArrayList<PendingReply> rows = new ArrayList<>();
		String[] args = {key.chanName, key.boardName, key.threadNumber,
				Long.toString(System.currentTimeMillis() - RETENTION)};
		try (Cursor c = db.rawQuery("SELECT id,after_num,started,accepted,signature FROM pending_replies "
				+ "WHERE " + THREAD + " AND accepted>=? ORDER BY id", args)) {
			while (c.moveToNext()) {
				rows.add(new PendingReply(c.getLong(0), PostNumber.parseNullable(c.getString(1)),
						c.getLong(2), c.getLong(3), c.getString(4)));
			}
		}
		return rows;
	}

	public static List<Post> resolve(ThreadKey key, List<PendingReply> snapshot, List<Post> posts, PostNumber op) {
		PostsDatabase flags = CommonDatabase.getInstance().getPosts();
		Set<PostNumber> owned = flags.getFlags(key.chanName, key.boardName, key.threadNumber, false).userPosts;
		Map<PendingReply, Post> matches = PendingReply.match(snapshot, posts, op, owned);
		if (matches.isEmpty()) return Collections.emptyList();
		List<Post> claimed = CommonDatabase.getInstance().execute(db -> claim(db, key, snapshot, matches));
		if (!claimed.isEmpty()) flags.notifyOwnPostsChanged(key);
		return claimed;
	}

	static List<Post> claim(SQLiteDatabase db, ThreadKey key, List<PendingReply> snapshot,
			Map<PendingReply, Post> matches) {
		db.beginTransaction();
		try {
			// A send, another reader or the history-clear action may have won while the request was running.
			if (!snapshot.equals(list(db, key))) return Collections.emptyList();
			for (Map.Entry<PendingReply, Post> match : matches.entrySet()) {
				if (!PostsDatabase.markIfUnowned(db, key, match.getValue().number)) return Collections.emptyList();
				db.delete(TABLE, "id=?", new String[] {Long.toString(match.getKey().id())});
			}
			db.setTransactionSuccessful();
			return new ArrayList<>(matches.values());
		} finally {
			db.endTransaction();
		}
	}
}
