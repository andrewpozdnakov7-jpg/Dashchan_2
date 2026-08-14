package com.mishiranu.dashchan.content;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Process;
import android.webkit.WebView;
import androidx.work.WorkManager;
import chan.content.ChanManager;
import chan.http.HttpClient;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.net.UserAgentProvider;
import com.mishiranu.dashchan.content.service.BackgroundWatcherWorker;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.Logger;
import java.io.File;
import java.util.List;

public class MainApplication extends Application {
	private static final String PROCESS_WEB_VIEW = "webview";
	private static final String PROCESS_TRANSLATION = "translation";
	private static final String KEY_REMOVED_AUTO_BUMP_CLEANUP = "removed_auto_bump_cleanup";

	private static MainApplication instance;

	public MainApplication() {
		instance = this;
	}

	private boolean checkProcess(String suffix) {
		return CommonUtils.equals(suffix, processSuffix);
	}

	public boolean isMainProcess() {
		return checkProcess(null);
	}

	private String processSuffix;

	@Override
	public void onCreate() {
		super.onCreate();

		String processName = Application.getProcessName();
		if (StringUtils.isEmpty(processName)) {
			ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
			List<ActivityManager.RunningAppProcessInfo> processes = activityManager != null
					? activityManager.getRunningAppProcesses() : null;
			if (processes != null) {
				int pid = Process.myPid();
				for (ActivityManager.RunningAppProcessInfo process : processes) {
					if (process.pid == pid) {
						processName = process.processName;
						break;
					}
				}
			}
		}
		if (!StringUtils.isEmpty(processName)) {
			int index = processName.indexOf(':');
			if (index >= 0) {
				processSuffix = StringUtils.nullIfEmpty(processName.substring(index + 1));
			}
		}
		if (checkProcess(PROCESS_WEB_VIEW) || checkProcess(PROCESS_TRANSLATION)) {
			WebView.setDataDirectorySuffix(processSuffix);
		}

		if (isMainProcess()) {
			Logger.init(this);
			FontManager.register(this);
			LauncherIconManager.apply(this, Preferences.getApplicationName());
			UserAgentProvider.initialize(this);
			ChanManager.getInstance();
			HttpClient.getInstance();
			CommonDatabase.getInstance();
			PagesDatabase.getInstance();
			ChanDatabase.getInstance();
			CacheManager.getInstance();
			ChanManager.getInstance().loadLibraries();
			BackgroundWatcherWorker.restoreSchedule(this);
			cleanupRemovedAutoBump();
		} else if (checkProcess(PROCESS_WEB_VIEW) || checkProcess(PROCESS_TRANSLATION)) {
			IOUtils.deleteRecursive(getIsolatedWebViewCacheDir());
		}
	}

	private void cleanupRemovedAutoBump() {
		if (Preferences.PREFERENCES.getBoolean(KEY_REMOVED_AUTO_BUMP_CLEANUP, false)) {
			return;
		}
		WorkManager.getInstance(this).cancelUniqueWork("auto-bump");
		File storageDirectory = new File(getFilesDir(), "storage");
		new File(storageDirectory, "auto_bump.json").delete();
		new File(storageDirectory, "auto_bump.backup.json").delete();
		new File(storageDirectory, "auto_bump.restore.json").delete();
		Preferences.PREFERENCES.edit().remove("auto_bump_enabled")
				.put(KEY_REMOVED_AUTO_BUMP_CLEANUP, true).close();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.deleteNotificationChannel("autoBump");
			}
		}
	}

	public static MainApplication getInstance() {
		return instance;
	}

	public Context getLocalizedContext() {
		return LocaleManager.getInstance().applyApplication(this);
	}

	public boolean isLowRam() {
		ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		return activityManager != null && activityManager.isLowRamDevice();
	}

	public File getSharedPrefsDir() {
		return new File(getCacheDir().getParentFile(), "shared_prefs");
	}

	private File getIsolatedWebViewCacheDir() {
		return new File(super.getCacheDir(), checkProcess(PROCESS_TRANSLATION) ? "translation" : "webview");
	}

	@Override
	public File getCacheDir() {
		if (checkProcess(PROCESS_WEB_VIEW) || checkProcess(PROCESS_TRANSLATION)) {
			File dir = new File(getIsolatedWebViewCacheDir(), "cache");
			dir.mkdirs();
			return dir;
		}
		return super.getCacheDir();
	}

	@Override
	public File getDir(String name, int mode) {
		if (checkProcess(PROCESS_WEB_VIEW) || checkProcess(PROCESS_TRANSLATION)) {
			File dir = new File(getIsolatedWebViewCacheDir(), name);
			dir.mkdirs();
			return dir;
		} else {
			return super.getDir(name, mode);
		}
	}

	@Override
	public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
		if ("http_auth.db".equals(name)) {
			// Create in-memory database for WebView
			return SQLiteDatabase.create(factory);
		} else {
			return super.openOrCreateDatabase(name, mode, factory);
		}
	}
}
