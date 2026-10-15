package com.mishiranu.dashchan.content.security;

import static com.mishiranu.dashchan.content.security.PrivacyResetFiles.clearChildren;
import static com.mishiranu.dashchan.content.security.PrivacyResetFiles.delete;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import com.mishiranu.dashchan.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal reset precedes application startup; optional approved SAF cleanup runs in the reset process. */
public final class PrivacyReset {
	private PrivacyReset() {}

	public static boolean requiresRecovery(Context context) {
		try {
			return SecurityStore.pending(context);
		} catch (IOException e) {
			// No normal stores/providers/components on an unreadable recovery record.
			return true;
		}
	}

	public static void run(Context context) throws IOException {
		run(context, false);
	}

	private static void run(Context context, boolean bootstrap) throws IOException {
		File security = SecurityStore.directory(context);
		try (RandomAccessFile mutex = new RandomAccessFile(new File(security, "reset.lock"), "rw");
				FileLock lock = mutex.getChannel().lock()) {
			if (!SecurityStore.pending(context)) return;
			stopOtherProcesses(context);
			if (!bootstrap) {
				ActivityManager manager = context.getSystemService(ActivityManager.class);
				if (manager == null) throw new IOException("Cannot clear application tasks");
				for (ActivityManager.AppTask task : manager.getAppTasks()) {
					ActivityManager.RecentTaskInfo info = task.getTaskInfo();
					if (info.baseActivity == null || !PrivacyResetActivity.class.getName().equals(info.baseActivity.getClassName())) {
						task.finishAndRemoveTask();
					}
				}
			}
			if (!SecurityStore.update(context, s -> s.optBoolean("reset_local_done"))) {
			JobScheduler scheduler = context.getSystemService(JobScheduler.class);
			if (scheduler != null) scheduler.cancelAll();
			NotificationManager notifications = context.getSystemService(NotificationManager.class);
			if (notifications != null) notifications.cancelAll();
			preservePresentationPreferences(context);
			File data = context.getDataDir().getCanonicalFile();
			// Every target is inside app-owned storage. No URI traversal and no symlink following.
			clearChildren(new File(data, "databases"), name -> false);
			clearChildren(new File(data, "shared_prefs"), name -> "preferences.xml".equals(name) || "startup-notices.xml".equals(name));
			File files = context.getFilesDir();
			clearChildren(files, name -> "storage".equals(name) || "translation".equals(name) || "wallpapers".equals(name));
			clearChildren(new File(files, "storage"), PrivacyResetPolicy::keepStorageFile);
			clearChildren(new File(files, "translation"), name -> "models".equals(name));
			clearChildren(context.getNoBackupFilesDir(), name -> "app-security-v1".equals(name));
			clearChildren(context.getCacheDir(), name -> false);
			for (File cache : context.getExternalCacheDirs()) {
				if (cache == null) throw new IOException("Application external storage unavailable");
				clearChildren(cache, name -> false);
			}
			for (File external : context.getExternalFilesDirs(null)) {
				if (external == null) throw new IOException("Application external storage unavailable");
				// This is the application's own temporary WebView screenshot, not downloaded media.
				if (external != null) {
					delete(new File(external, "webview.png"));
					// AdvancedPreferences reads cookies from this app-owned legacy file.
					for (String name : new String[]{"advanced.json", "advanced.json.bak", "advanced.json.backup"}) {
						delete(new File(external, name));
					}
				}
			}
			File[] root = data.listFiles();
			if (root == null) throw new IOException("Cannot enumerate application storage");
			for (File entry : root) {
				if (entry.getName().startsWith("app_webview")) delete(entry);
			}
			for (File cache : context.getExternalCacheDirs()) {
				if (cache == null) continue;
				File parent = cache.getParentFile();
				if (parent != null) { delete(new File(parent, "logs")); delete(new File(parent, "errors")); }
			}
				SecurityStore.update(context, s -> { s.put("reset_local_done", true); return null; });
			}
			// The internal profile is now empty. Allow the UI to present a retry/recovery screen
			// rather than crash forever when a removable SD card or SAF permission is unavailable.
			if (bootstrap) return;
			String tree = SecurityStore.update(context, s -> s.optString("reset_tree", null));
			if (tree != null) DownloadTreeCleanup.clear(context, tree);
			SecurityStore.update(context, s -> { s.remove("external_cleanup_incomplete"); return null; });
			complete(context);
		}
	}

	public static void complete(Context context) throws IOException {
		complete(context, () -> true);
	}
	static void complete(Context context, java.util.function.BooleanSupplier authorization) throws IOException {
			if (!authorization.getAsBoolean()) throw new IOException("Recovery authorization expired");
			for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
				if (!authorization.getAsBoolean()) throw new IOException("Recovery authorization expired");
				int flags = (permission.isReadPermission() ? android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
						| (permission.isWritePermission() ? android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
				if (flags != 0) context.getContentResolver().releasePersistableUriPermission(permission.getUri(), flags);
			}
			SecurityStore.authorizedUpdate(context, authorization, s -> {
				if (!s.optBoolean("reset_local_done")) throw new IOException("Internal cleanup is incomplete");
				s.remove("foreground"); s.put("unlocked", false);
				s.put("empty_launch", SecurityStore.encode(PinCredential.salt()));
				s.put("empty_launch_generation", s.optLong("profile_generation"));
				s.put("empty_launch_boot", android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1));
				s.put("empty_launch_until", android.os.SystemClock.elapsedRealtime() + 60_000);
				s.remove("reset_pending"); s.remove("reset_local_done"); s.remove("reset_tree");
				s.remove("delete_downloads_uri"); s.remove("delete_downloads_label");
				return null;
			});
	}

	static void stopOtherProcesses(Context context) throws IOException {
		ActivityManager manager = context.getSystemService(ActivityManager.class);
		List<ActivityManager.RunningAppProcessInfo> processes = manager != null ? manager.getRunningAppProcesses() : null;
		if (processes == null) throw new IOException("Cannot stop application writers");
		for (ActivityManager.RunningAppProcessInfo process : processes) {
			if (process.uid == Process.myUid() && process.pid != Process.myPid()
					&& (process.processName.equals(context.getPackageName())
					|| process.processName.startsWith(context.getPackageName() + ":"))) {
				Process.killProcess(process.pid);
				// Do not proceed while a killed writer still has files open.
				long end = android.os.SystemClock.elapsedRealtime() + 5000;
				while (new File("/proc/" + process.pid).exists()) {
					if (android.os.SystemClock.elapsedRealtime() >= end) throw new IOException("Application writer still running");
					android.os.SystemClock.sleep(20);
				}
			}
		}
	}

	private static void preservePresentationPreferences(Context context) throws IOException {
		SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
		Map<String, ?> original = preferences.getAll();
		SharedPreferences.Editor editor = preferences.edit().clear();
		for (Map.Entry<String, ?> entry : original.entrySet()) {
			String key = entry.getKey(); Object value = entry.getValue();
			if (!PrivacyResetPolicy.keepPreference(key)) continue;
			if (value instanceof String) editor.putString(key, (String) value);
			else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
			else if (value instanceof Integer) editor.putInt(key, (Integer) value);
			else if (value instanceof Long) editor.putLong(key, (Long) value);
			else if (value instanceof Float) editor.putFloat(key, (Float) value);
			else if (value instanceof Set<?>) {
				java.util.HashSet<String> copy = new java.util.HashSet<>();
				for (Object item : (Set<?>) value) if (item instanceof String) copy.add((String) item);
				editor.putStringSet(key, copy);
			}
		}
		// Cleared local credentials must not trigger fresh background registrations/checks.
		editor.putBoolean("track_my_posts", false).putBoolean("reply_push_enabled", false)
				.putBoolean("background_reply_check", false).putBoolean("tracked_replies_local_check", false)
				.putInt("watcher_refresh_interval", 0);
		if (!editor.commit()) throw new IOException("Cannot save retained preferences");
		if (BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION) {
			SharedPreferences notices = context.getSharedPreferences("startup-notices", Context.MODE_PRIVATE);
			int acknowledgements = notices.getInt("material3-acknowledgements", 0);
			if (!notices.edit().clear().putInt("material3-acknowledgements", acknowledgements).commit()) {
				throw new IOException("Cannot retain presentation notice state");
			}
		}
	}
}
