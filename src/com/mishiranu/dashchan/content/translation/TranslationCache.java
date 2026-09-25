package com.mishiranu.dashchan.content.translation;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Worker-confined, disposable cache. Not part of backups or the browser's private mode. */
final class TranslationCache {
	private static final long MAX_BYTES = 64L * 1024 * 1024;
	private static final long MAX_AGE = 90L * 24 * 60 * 60 * 1000;
	private static final int MAX_ENTRY_BYTES = 512 * 1024;
	private SQLiteDatabase database;

	static final class Result {
		final String subject;
		final String html;
		Result(String subject, String html) {
			this.subject = subject;
			this.html = html;
		}
	}

	private SQLiteDatabase database() {
		if (database == null) {
			SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(
					MainApplication.getInstance().getNoBackupFilesDir(), "translation-cache-v1.db"), null);
			try {
				db.execSQL("CREATE TABLE IF NOT EXISTS translations (key TEXT PRIMARY KEY, subject TEXT NOT NULL, " +
						"html TEXT NOT NULL, bytes INTEGER NOT NULL, used INTEGER NOT NULL, created INTEGER NOT NULL)");
				db.execSQL("CREATE INDEX IF NOT EXISTS translations_used ON translations(used)");
				database = db;
			} finally {
				if (database != db) db.close();
			}
		}
		return database;
	}

	String key(String scope, TranslationEngine engine, TranslationModel.Direction direction, String subject, String html) {
		String packageName = engine == TranslationEngine.GOOGLE ? "io.dashchan2.addon.googletranslate"
				: engine == TranslationEngine.GEMINI_NANO ? "com.google.android.aicore" : null;
		long packageVersion = 0;
		if (packageName != null) {
			try {
				packageVersion = MainApplication.getInstance().getPackageManager()
						.getPackageInfo(packageName, 0).getLongVersionCode();
			} catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException ignored) {}
		}
		return TranslationCacheKey.create("html-v1", scope, engine.getCacheKey(direction),
				Integer.toString(BuildConfig.VERSION_CODE), Long.toString(packageVersion), subject, html);
	}

	Result get(String key) {
		try {
			long now = System.currentTimeMillis();
			SQLiteDatabase db = database();
			try (Cursor cursor = db.query("translations", new String[] {"subject", "html", "created"},
					"key=?", new String[] {key}, null, null, null)) {
				if (!cursor.moveToFirst()) return null;
				if (now - cursor.getLong(2) > MAX_AGE) {
					db.delete("translations", "key=?", new String[] {key});
					return null;
				}
				Result result = new Result(cursor.getString(0), cursor.getString(1));
				ContentValues values = new ContentValues();
				values.put("used", now);
				db.update("translations", values, "key=?", new String[] {key});
				return result;
			}
		} catch (RuntimeException e) {
			TranslationDiagnostics.error("cache", "read_failed", "type", e.getClass().getSimpleName());
			return null; // Cache failure must not prevent translation.
		}
	}

	void put(String key, String subject, String html) {
		if (html == null) return;
		subject = subject != null ? subject : "";
		long bytes = (long) subject.getBytes(StandardCharsets.UTF_8).length + html.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_ENTRY_BYTES) return;
		try {
			SQLiteDatabase db = database();
			long now = System.currentTimeMillis();
			db.beginTransaction();
			try {
				ContentValues values = new ContentValues();
				values.put("key", key);
				values.put("subject", subject);
				values.put("html", html);
				values.put("bytes", bytes);
				values.put("used", now);
				values.put("created", now);
				db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
				db.delete("translations", "created<?", new String[] {Long.toString(now - MAX_AGE)});
				db.execSQL("DELETE FROM translations WHERE key IN (SELECT key FROM translations ORDER BY used DESC LIMIT -1 OFFSET 10000)");
				long total = DatabaseUtils.longForQuery(db, "SELECT COALESCE(SUM(bytes),0) FROM translations", null);
				if (total > MAX_BYTES) {
					try (Cursor cursor = db.query("translations", new String[] {"key", "bytes"}, null, null, null, null, "used ASC")) {
						while (total > MAX_BYTES && cursor.moveToNext()) {
							total -= cursor.getLong(1);
							db.delete("translations", "key=?", new String[] {cursor.getString(0)});
						}
					}
				}
				db.setTransactionSuccessful();
			} finally {
				db.endTransaction();
			}
		} catch (RuntimeException e) {
			TranslationDiagnostics.error("cache", "write_failed", "type", e.getClass().getSimpleName());
		}
	}

	boolean clear() {
		try {
			SQLiteDatabase db = database();
			db.execSQL("PRAGMA secure_delete=ON");
			db.delete("translations", null, null);
			db.execSQL("VACUUM");
			return true;
		} catch (RuntimeException e) {
			TranslationDiagnostics.error("cache", "clear_failed", "type", e.getClass().getSimpleName());
			return false;
		}
	}
}
