package com.mishiranu.dashchan.content;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import chan.content.ChanManager;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UpdaterActivity extends StateActivity {
	private static final String EXTRA_FILES = "files";

	private static final String EXTRA_INDEX = "index";
	private static final String RESUME_PREFERENCES = "self_update_resume";
	private static final String KEY_STARTED_AT = "started_at";
	private static final String KEY_PREVIOUS_VERSION_CODE = "previous_version_code";
	private static final long RESUME_MAX_AGE_MS = 2L * 60L * 60L * 1000L;

	private int index = 0;

	private final ActivityResultLauncher<Intent> packageInstaller = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), this::onInstallationResult);

	private List<String> getFiles() {
		return getIntent().getStringArrayListExtra(EXTRA_FILES);
	}

	private static boolean isClientFile(String name) {
		return name != null && name.startsWith(ChanManager.EXTENSION_NAME_CLIENT + "-")
				&& name.endsWith(".apk");
	}

	private boolean isInstallingClient() {
		List<String> files = getFiles();
		return files != null && index < files.size() && isClientFile(files.get(index));
	}

	private static void markClientInstall(Context context) {
		// Commit before opening the installer: replacing this package may kill our process.
		context.getSharedPreferences(RESUME_PREFERENCES, Context.MODE_PRIVATE).edit()
				.putLong(KEY_STARTED_AT, System.currentTimeMillis())
				.putInt(KEY_PREVIOUS_VERSION_CODE, BuildConfig.VERSION_CODE)
				.commit();
	}

	private static void clearClientInstall(Context context) {
		context.getSharedPreferences(RESUME_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(C.NOTIFICATION_ID_UPDATES);
		if (savedInstanceState == null) {
			performInstallation();
		} else {
			index = savedInstanceState.getInt(EXTRA_INDEX);
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(EXTRA_INDEX, index);
	}

	private void performInstallation() {
		List<String> files = getFiles();
		if (files != null && files.size() > index) {
			File file = FileProvider.getUpdatesFile(files.get(index));
			if (file == null) {
				if (isInstallingClient()) {
					clearClientInstall(this);
				}
				index++;
				performInstallation();
			} else {
				Uri uri = FileProvider.convertUpdatesUri(Uri.fromFile(file));
				if (isInstallingClient()) {
					markClientInstall(this);
				}
				@SuppressWarnings("deprecation")
				String action = Intent.ACTION_INSTALL_PACKAGE;
				packageInstaller.launch(new Intent(action)
						.setDataAndType(uri, "application/vnd.android.package-archive")
						.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
						.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
						.putExtra(Intent.EXTRA_RETURN_RESULT, true));
			}
		} else {
			finish();
		}
	}

	/** Only the app's own successful replacement may offer to reopen its saved page. */
	public static class AppUpdatedReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
				return;
			}
			android.content.SharedPreferences preferences = context.getSharedPreferences(
					RESUME_PREFERENCES, Context.MODE_PRIVATE);
			long startedAt = preferences.getLong(KEY_STARTED_AT, 0L);
			int previousVersionCode = preferences.getInt(KEY_PREVIOUS_VERSION_CODE, 0);
			preferences.edit().clear().commit();
			long age = System.currentTimeMillis() - startedAt;
			if (startedAt <= 0L || age < 0L || age > RESUME_MAX_AGE_MS
					|| BuildConfig.VERSION_CODE <= previousVersionCode) {
				return;
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(
					Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				return;
			}
			NotificationManager notificationManager = (NotificationManager)
					context.getSystemService(Context.NOTIFICATION_SERVICE);
			notificationManager.createNotificationChannel(AndroidUtils.createHeadsUpNotificationChannel(
					C.NOTIFICATION_CHANNEL_UPDATES, context.getString(R.string.updates)));
			Intent openApp = new Intent(context, MainActivity.class)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			PendingIntent pendingIntent = PendingIntent.getActivity(context,
					C.NOTIFICATION_ID_UPDATE_INSTALLED, openApp,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			NotificationCompat.Builder notification = new NotificationCompat.Builder(
					context, C.NOTIFICATION_CHANNEL_UPDATES)
					.setSmallIcon(android.R.drawable.stat_sys_download_done)
					.setContentTitle(context.getString(R.string.open_slooop_after_update))
					.setContentText(context.getString(R.string.update_installed_open_app))
					.setContentIntent(pendingIntent)
					.setAutoCancel(true);
			notificationManager.notify(C.NOTIFICATION_ID_UPDATE_INSTALLED, notification.build());
		}
	}

	// Hidden error code in PackageManager
	private static final int INSTALL_FAILED_INVALID_APK = -2;

	private void onInstallationResult(ActivityResult result) {
		int resultCode = result.getResultCode();
		Intent data = result.getData();
		if (resultCode == RESULT_OK) {
			index++;
			performInstallation();
		} else if (resultCode == RESULT_FIRST_USER && data != null &&
				data.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) == INSTALL_FAILED_INVALID_APK) {
			// Retry on failure. Workaround for Android 10+ bug in FLAG_GRANT_READ_URI_PERMISSION behavior:
			// sometimes the flag doesn't take effect and package installer is unable to access the package file.
			performInstallation();
		} else {
			if (isInstallingClient()) {
				clearClientInstall(this);
			}
			finish();
		}
	}

	private static Connection activeConnection;

	private static Intent createInstallIntent(Context context, ArrayList<String> files) {
		return new Intent(context, UpdaterActivity.class)
				.putStringArrayListExtra(EXTRA_FILES, files)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
	}

	private static void notifyInstallReady(Context context, ArrayList<String> files) {
		NotificationManager notificationManager = (NotificationManager)
				context.getSystemService(NOTIFICATION_SERVICE);
		notificationManager.createNotificationChannel(AndroidUtils.createHeadsUpNotificationChannel
				(C.NOTIFICATION_CHANNEL_UPDATES, context.getString(R.string.updates)));
		PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
				createInstallIntent(context, files),
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context, C.NOTIFICATION_CHANNEL_UPDATES)
				.setSmallIcon(android.R.drawable.stat_sys_download_done)
				.setContentTitle(context.getString(R.string.update_downloaded))
				.setContentText(context.getString(R.string.tap_to_install_update__sentence))
				.setContentIntent(pendingIntent)
				.setAutoCancel(true)
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setVibrate(new long[0]);
		notificationManager.notify(C.NOTIFICATION_ID_UPDATES, builder.build());
	}

	private static class Connection implements ServiceConnection, DownloadService.Callback {
		private final Context context;
		private final List<DownloadService.DownloadItem> downloadItems;
		private final HashMap<String, Boolean> status = new HashMap<>();

		public Connection(Context context, List<DownloadService.DownloadItem> downloadItems) {
			this.context = context;
			this.downloadItems = downloadItems;
			context.bindService(new Intent(context, DownloadService.class), this, BIND_AUTO_CREATE);
		}

		private DownloadService.Binder binder;

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder binder) {
			this.binder = (DownloadService.Binder) binder;
			this.binder.register(this);
			this.binder.downloadDirect(DataFile.Target.UPDATES, null, true, downloadItems);
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			if (this == activeConnection) {
				activeConnection = null;
				if (binder != null) {
					binder.unregister(this);
					binder = null;
				}
			}
		}

		private boolean finish() {
			if (this == activeConnection) {
				activeConnection = null;
				if (binder != null) {
					binder.unregister(this);
					binder = null;
					context.unbindService(this);
					return true;
				}
			}
			return false;
		}

		@Override
		public void onFinishDownloading(boolean success, DataFile.Target target, String path, String name) {
			if (target == DataFile.Target.UPDATES && StringUtils.isEmpty(path)) {
				status.put(name, success);
			}
			if (success) {
				boolean successAll = true;
				for (DownloadService.DownloadItem downloadItem : downloadItems) {
					Boolean status = this.status.get(downloadItem.name);
					if (status == null || !status) {
						successAll = false;
						break;
					}
				}
				if (successAll) {
					if (finish()) {
						ArrayList<String> files = new ArrayList<>(downloadItems.size());
						File directory = FileProvider.getUpdatesDirectory();
						for (DownloadService.DownloadItem downloadItem : downloadItems) {
							if (!new File(directory, downloadItem.name).exists()) {
								break;
							}
							files.add(downloadItem.name);
						}
						if (files.size() == downloadItems.size()) {
							notifyInstallReady(context, files);
							try {
								context.startActivity(createInstallIntent(context, files));
							} catch (RuntimeException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
		}

		@Override
		public void onCleanup() {
			finish();
		}

		public void cancel() {
			if (binder != null) {
				binder.unregister(this);
				binder = null;
				context.unbindService(this);
			}
		}
	}

	public static class Request {
		public final String extensionName;
		public final String versionName;
		public final Uri uri;
		public final byte[] sha256sum;
		public final ChanManager.Fingerprints checkFingerprints;

		public Request(String extensionName, String versionName, Uri uri,
				byte[] sha256sum, ChanManager.Fingerprints checkFingerprints) {
			this.extensionName = extensionName;
			this.versionName = versionName;
			this.uri = uri;
			this.sha256sum = sha256sum;
			this.checkFingerprints = checkFingerprints;
		}
	}

	public static void startUpdater(List<Request> requests) {
		startUpdater(requests, false);
	}

	public static void startUpdaterAfterExtensionInstallConsent(List<Request> requests) {
		startUpdater(requests, true);
	}

	private static void startUpdater(List<Request> requests, boolean extensionInstallConsent) {
		if (BuildConfig.REQUIRE_EXTENSION_INSTALL_CONSENT && !extensionInstallConsent) {
			return;
		}
		DownloadService.DownloadItem clientDownloadItem = null;
		ArrayList<DownloadService.DownloadItem> downloadItems = new ArrayList<>();
		for (Request request : requests) {
			if (!BuildConfig.ALLOW_APPLICATION_SELF_UPDATE &&
					ChanManager.EXTENSION_NAME_CLIENT.equals(request.extensionName)) {
				continue;
			}
			String name = request.extensionName + "-" + request.versionName + ".apk";
			DownloadService.DownloadItem downloadItem = new DownloadService.DownloadItem(null,
					request.uri, name, request.sha256sum, request.checkFingerprints);
			if (ChanManager.EXTENSION_NAME_CLIENT.equals(request.extensionName)) {
				clientDownloadItem = downloadItem;
			} else {
				downloadItems.add(downloadItem);
			}
		}
		if (clientDownloadItem != null) {
			downloadItems.add(clientDownloadItem);
		}
		if (downloadItems.isEmpty()) {
			return;
		}
		if (activeConnection != null) {
			activeConnection.cancel();
		}
		activeConnection = new Connection(MainApplication.getInstance(), downloadItems);
	}
}
