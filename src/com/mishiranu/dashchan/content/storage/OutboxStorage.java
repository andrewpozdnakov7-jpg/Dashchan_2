package com.mishiranu.dashchan.content.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import android.util.Log;
import chan.text.JsonSerial;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.Hasher;
import chan.util.StringUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Single-worker durable journal. No cookies, captcha tokens or passwords are serialized here. */
public final class OutboxStorage {
	private static final OutboxStorage INSTANCE = new OutboxStorage();
	public static OutboxStorage getInstance() { return INSTANCE; }
	private final ExecutorService executor = ConcurrentUtils.newSingleThreadPool(3000, "Outbox", null);
	private SQLiteDatabase database;
	private File root;

	/** Do not log exception messages: SQLite errors may include draft values or local paths. */
	public static void logFailure(String stage, Throwable error) {
		for (int i = 0; i < 8 && error.getCause() != null && error.getCause() != error; i++) {
			error = error.getCause();
		}
		StringBuilder trace = new StringBuilder();
		int count = 0;
		for (StackTraceElement frame : error.getStackTrace()) {
			if (frame.getClassName().startsWith("com.mishiranu.dashchan.")) {
				trace.append(' ').append(frame.getClassName()).append('#').append(frame.getMethodName())
						.append(':').append(frame.getLineNumber());
				if (++count == 6) break;
			}
		}
		Log.w("Outbox", "failure stage=" + stage + " type=" + error.getClass().getSimpleName() + trace);
	}

	public static final class AttachmentRecoveryException extends IOException {
		private static final long serialVersionUID = 1L;
		AttachmentRecoveryException() { super("Cannot restore outgoing attachment"); }
	}

	public static final class Entry {
		public final String id, chanName, boardName, threadNumber, postNumber;
		public final OutboxState state;
		public final long updated;
		Entry(Cursor cursor) {
			id = cursor.getString(0);
			chanName = cursor.getString(1);
			boardName = cursor.getString(2);
			threadNumber = cursor.getString(3);
			postNumber = cursor.getString(4);
			state = OutboxState.valueOf(cursor.getString(5));
			updated = cursor.getLong(6);
		}
	}

	private SQLiteDatabase database() {
		if (database == null) {
			root = new File(MainApplication.getInstance().getNoBackupFilesDir(), "outbox-v1");
			if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Outbox directory unavailable");
			SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(root, "journal.db"), null);
			String stage = "schema";
			try {
				db.execSQL("CREATE TABLE IF NOT EXISTS outgoing (id TEXT PRIMARY KEY, chan TEXT NOT NULL, board TEXT, " +
						"thread TEXT, post TEXT, state TEXT NOT NULL, updated INTEGER NOT NULL, draft BLOB)");
				stage = "configure_sync";
				db.execSQL("PRAGMA synchronous=FULL");
				stage = "configure_secure_delete";
				// This assignment RETURNS a row; execSQL rejects row-returning statements on Android.
				DatabaseUtils.longForQuery(db, "PRAGMA secure_delete=ON", null);
				stage = "recover_states";
				db.beginTransaction();
				try {
					for (OutboxState state : OutboxState.values()) {
						if (state.isActive()) {
							ContentValues values = new ContentValues();
							values.put("state", state.afterProcessDeath().name());
							db.update("outgoing", values, "state=?", new String[] {state.name()});
						}
					}
					db.setTransactionSuccessful();
				} finally { db.endTransaction(); }
				database = db;
				stage = "cleanup_sent_attachments";
				// Finish cleanup if the previous process stopped after committing the acknowledgement.
				try (Cursor cursor = db.query("outgoing", new String[] {"id"}, "state=?",
						new String[] {OutboxState.SENT.name()}, null, null, null)) {
					while (cursor.moveToNext()) deleteAttachments(cursor.getString(0));
				}
			} catch (RuntimeException e) {
				logFailure(stage, e);
				throw e;
			} finally { if (database != db) db.close(); }
		}
		return database;
	}

	public static DraftsStorage.PostDraft snapshot(DraftsStorage.PostDraft draft) {
		return new DraftsStorage.PostDraft(draft.chanName, draft.boardName, draft.threadNumber,
				draft.name, draft.email, null, draft.subject, draft.comment, draft.commentCarriage,
				draft.attachmentDrafts != null ? new ArrayList<>(draft.attachmentDrafts) : null,
				draft.optionSage, draft.optionSpoiler, draft.optionOriginalPoster, draft.userIcon);
	}

	public Future<Void> enqueue(String id, DraftsStorage.PostDraft draft, Map<String, File> attachments) {
		return executor.submit(() -> {
			SQLiteDatabase db = database();
			// Completed metadata can expire; never silently evict an unresolved outgoing message.
			db.execSQL("DELETE FROM outgoing WHERE state='SENT' AND id NOT IN " +
					"(SELECT id FROM outgoing WHERE state='SENT' ORDER BY updated DESC LIMIT 200)");
			if (DatabaseUtils.queryNumEntries(db, "outgoing") >= 1000) throw new IOException("Outbox is full");
			byte[] bytes;
			try (JsonSerial.Writer writer = JsonSerial.writer()) {
				draft.serialize(writer);
				bytes = writer.build();
			}
			if (bytes.length > 1024 * 1024) throw new IOException("Outbox draft is too large");
			ContentValues values = new ContentValues();
			values.put("id", id);
			values.put("chan", draft.chanName);
			values.put("board", draft.boardName);
			values.put("thread", draft.threadNumber);
			values.put("state", OutboxState.PREPARING.name());
			values.put("updated", System.currentTimeMillis());
			values.put("draft", bytes);
			db.insertOrThrow("outgoing", null, values);
			try {
				if (draft.attachmentDrafts != null) {
					for (DraftsStorage.AttachmentDraft attachment : draft.attachmentDrafts) {
						File source = attachments.get(attachment.hash);
						if (source == null || !source.isFile()) throw new IOException("Outgoing attachment missing");
						File target = attachmentFile(id, attachment.hash);
						if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
							throw new IOException("Outgoing attachment directory unavailable");
						}
						AtomicFile atomicFile = new AtomicFile(target);
						FileOutputStream output = atomicFile.startWrite();
						try (FileInputStream input = new FileInputStream(source)) {
							String hash = StringUtils.formatHex(Hasher.getInstanceSha256().calculateAndCopy(input, output));
							if (!attachment.hash.equals(hash)) throw new IOException("Outgoing attachment changed");
							atomicFile.finishWrite(output);
						} catch (Exception e) {
							atomicFile.failWrite(output);
							throw e;
						}
					}
				}
				setState(id, OutboxState.READY);
				return null;
			} catch (Exception e) {
				setState(id, OutboxState.INTERRUPTED);
				throw e;
			}
		});
	}

	public File attachmentFile(String id, String hash) {
		if (!id.matches("[0-9a-f-]{36}") || hash == null || !hash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid outgoing attachment identity");
		}
		return new File(new File(new File(MainApplication.getInstance().getNoBackupFilesDir(), "outbox-v1"), id), hash);
	}

	private OutboxState state(String id) {
		try (Cursor cursor = database().query("outgoing", new String[] {"state"}, "id=?", new String[] {id}, null, null, null)) {
			return cursor.moveToFirst() ? OutboxState.valueOf(cursor.getString(0)) : null;
		}
	}

	private void setState(String id, OutboxState state) {
		ContentValues values = new ContentValues();
		values.put("state", state.name());
		values.put("updated", System.currentTimeMillis());
		if (database().update("outgoing", values, "id=?", new String[] {id}) != 1) {
			throw new IllegalStateException("Outgoing entry missing");
		}
	}

	/** Wait only on a send worker, before calling the transport. */
	public void beginSend(String id) throws Exception {
		executor.submit(() -> {
			OutboxState state = state(id);
			if (state != OutboxState.READY && state != OutboxState.WAITING) throw new IllegalStateException("Outgoing entry not ready");
			setState(id, OutboxState.SENDING);
		}).get();
	}

	public void accepted(String id, String thread, String post) {
		try {
			executor.submit(() -> {
				ContentValues values = new ContentValues();
				values.put("state", OutboxState.SENT.name());
				values.put("updated", System.currentTimeMillis());
				if (thread != null) values.put("thread", thread);
				values.put("post", post);
				values.putNull("draft");
				if (database().update("outgoing", values, "id=?", new String[] {id}) != 1) {
					throw new IllegalStateException("Outgoing entry missing");
				}
				deleteAttachments(id);
			}).get();
		} catch (Exception e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			// Never turn an acknowledged post into a retryable failure because local storage failed.
			logFailure("persist_acknowledgement", e);
		}
	}

	public void finish(String id, OutboxState result) {
		run(() -> {
			OutboxState previous = state(id);
			if (previous != null && previous != OutboxState.SENT) {
				setState(id, result == null ? previous.afterCancellation()
						: result == OutboxState.UNKNOWN_RESULT && previous != OutboxState.SENDING ? OutboxState.FAILED : result);
			}
			return null;
		}, null, null);
	}

	public void list(Consumer<List<Entry>> callback, Consumer<Exception> failure) {
		run(() -> {
			ArrayList<Entry> result = new ArrayList<>();
			try (Cursor cursor = database().query("outgoing", new String[] {"id", "chan", "board", "thread", "post", "state", "updated"},
					null, null, null, null, "updated DESC")) {
				while (cursor.moveToNext()) result.add(new Entry(cursor));
			}
			return result;
		}, callback, failure);
	}

	public void restore(String id, boolean textOnly, Consumer<DraftsStorage.PostDraft> callback, Consumer<Exception> failure) {
		run(() -> {
			OutboxState state = state(id);
			if (state == null || state.isActive() || state == OutboxState.SENT) throw new IOException("Outgoing entry cannot be restored");
			DraftsStorage.PostDraft draft;
			try (Cursor cursor = database().query("outgoing", new String[] {"draft"}, "id=?", new String[] {id}, null, null, null)) {
				if (!cursor.moveToFirst() || cursor.isNull(0)) throw new IOException("Outgoing draft missing");
				try (JsonSerial.Reader reader = JsonSerial.reader(cursor.getBlob(0))) {
					draft = DraftsStorage.PostDraft.deserialize(reader);
				}
			}
			if (draft == null) throw new IOException("Invalid outgoing draft");
			if (textOnly) {
				return new DraftsStorage.PostDraft(draft.chanName, draft.boardName, draft.threadNumber,
						draft.name, draft.email, null, draft.subject, draft.comment, draft.commentCarriage,
						null, draft.optionSage, draft.optionSpoiler, draft.optionOriginalPoster, draft.userIcon);
			}
			if (draft.attachmentDrafts != null) {
				for (DraftsStorage.AttachmentDraft attachment : draft.attachmentDrafts) {
					File file = attachmentFile(id, attachment.hash);
					if (!file.isFile() || !attachment.hash.equals(DraftsStorage.getInstance().storeAttachmentFile(FileHolder.obtain(file)))) {
						throw new AttachmentRecoveryException();
					}
				}
			}
			return draft;
		}, callback, failure);
	}

	public void delete(String id, Runnable callback, Consumer<Exception> failure) {
		run(() -> {
			OutboxState state = state(id);
			if (state != null && state.isActive()) throw new IOException("Outgoing entry is active");
			database().delete("outgoing", "id=?", new String[] {id});
			deleteAttachments(id);
			return null;
		}, ignored -> callback.run(), failure);
	}

	private void deleteAttachments(String id) {
		if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
		File directory = new File(root, id);
		File[] files = directory.listFiles();
		if (files != null) for (File file : files) if (file.isFile()) file.delete();
		directory.delete();
	}

	private <T> void run(Callable<T> operation, Consumer<T> callback, Consumer<Exception> failure) {
		executor.execute(() -> {
			try {
				T result = operation.call();
				if (callback != null) ConcurrentUtils.HANDLER.post(() -> callback.accept(result));
			} catch (Exception e) {
				logFailure("journal_operation", e);
				if (failure != null) ConcurrentUtils.HANDLER.post(() -> failure.accept(e));
			}
		});
	}
}
