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
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.ViewModelProvider;
import chan.content.ChanManager;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.ui.DownloadedUpdatePrompt;
import com.mishiranu.dashchan.content.update.DownloadedUpdate;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.io.File;
import java.io.IOException;
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
	private UpdateInstallSession.Model installModel;
	private boolean resumed;
	private boolean permissionRequest;
	private String confirmationLaunched;

	private final ActivityResultLauncher<Intent> packageInstaller = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				// Session status, not the confirmation Activity result, is authoritative.
				if (resumed) performInstallation();
			});
	private final ActivityResultLauncher<Intent> unknownSources = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				permissionRequest = false;
				if (getPackageManager().canRequestPackageInstalls()) performInstallation();
				else { android.util.Log.i("UpdateInstaller", "install_permission_denied"); finish(); }
			});

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

	static void markClientInstall(Context context) {
		// Commit before opening the installer: replacing this package may kill our process.
		context.getSharedPreferences(RESUME_PREFERENCES, Context.MODE_PRIVATE).edit()
				.putLong(KEY_STARTED_AT, System.currentTimeMillis())
				.putInt(KEY_PREVIOUS_VERSION_CODE, BuildConfig.VERSION_CODE)
				.commit();
	}

	static void clearClientInstall(Context context) {
		context.getSharedPreferences(RESUME_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (com.mishiranu.dashchan.content.security.AppSecurity.deferIfLocked(this)) return;

		((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(C.NOTIFICATION_ID_UPDATES);
		if (savedInstanceState != null) {
			index = savedInstanceState.getInt(EXTRA_INDEX);
			permissionRequest = savedInstanceState.getBoolean("permission_request");
			confirmationLaunched = savedInstanceState.getString("confirmation_launched");
		}
		installModel = new ViewModelProvider(this).get(UpdateInstallSession.Model.class);
		installModel.recoverPreparation();
		readPendingConfirmation(getIntent());
		installModel.changed.observe(this, changed -> {
			if (resumed) performInstallation();
		});
	}

	@Override protected void onResume() {
		super.onResume();
		if (installModel == null || isFinishing()) return;
		resumed = true;
		installModel.foreground = true;
		performInstallation();
	}

	@Override protected void onPause() {
		resumed = false;
		if (installModel != null) installModel.foreground = false;
		super.onPause();
	}

	@Override protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		readPendingConfirmation(intent);
		if (resumed) performInstallation();
	}

	private void readPendingConfirmation(Intent intent) {
		String token = UpdateInstallSession.token(this);
		if (token != null && token.equals(intent.getStringExtra(UpdateInstallSession.EXTRA_TOKEN))) {
			installModel.confirmation = AndroidUtils.getParcelableExtra(intent,
					UpdateInstallSession.EXTRA_CONFIRMATION, Intent.class);
			intent.removeExtra(UpdateInstallSession.EXTRA_CONFIRMATION);
			confirmationLaunched = null;
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(EXTRA_INDEX, index);
		outState.putBoolean("permission_request", permissionRequest);
		outState.putString("confirmation_launched", confirmationLaunched);
	}

	private void performInstallation() {
		if (com.mishiranu.dashchan.content.security.SecurityStore.locked(this)) return;
		if (!resumed || isFinishing() || permissionRequest) return;
		String token = UpdateInstallSession.token(this);
		if (token != null) {
			// The durable session owns the exact queue, including across process recreation.
			getIntent().putStringArrayListExtra(EXTRA_FILES, UpdateInstallSession.files(this));
			index = UpdateInstallSession.index(this);
			int status = UpdateInstallSession.status(this);
			android.util.Log.i("UpdateInstaller", "resume_session status=" + status);
			if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
				if (installModel.confirmation != null && !token.equals(confirmationLaunched)) {
					Intent confirmation = installModel.confirmation;
					installModel.confirmation = null;
					confirmationLaunched = token;
					try { packageInstaller.launch(confirmation); }
					catch (RuntimeException e) {
						android.util.Log.w("UpdateInstaller", "confirmation_failed type="
								+ e.getClass().getSimpleName());
						UpdateInstallSession.cancel(this, token);
						clearClientInstall(this);
						finish();
					}
				} else if (installModel.confirmation == null && confirmationLaunched == null) {
					// Process died before confirmation, with notifications unavailable/dismissed.
					// Reopen a fresh session rather than silently waiting forever or auto-installing.
					UpdateInstallSession.cancel(this, token);
					clearClientInstall(this);
					performInstallation();
				}
				return;
			}
			if (status < PackageInstaller.STATUS_SUCCESS) return;
			UpdateInstallSession.clear(this, token);
			confirmationLaunched = null;
			if (status != PackageInstaller.STATUS_SUCCESS) {
				clearClientInstall(this);
				if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
					Toast.makeText(this, R.string.unknown_error, Toast.LENGTH_LONG).show();
				}
				finish();
				return;
			}
			index++;
		}
		List<String> files = getFiles();
		if (files != null && index >= 0 && files.size() > index) {
			File file = FileProvider.getUpdatesFile(files.get(index));
			if (file == null || !file.isFile()) {
				android.util.Log.w("UpdateInstaller", "missing_apk client=" + isInstallingClient());
				if (isInstallingClient()) {
					clearClientInstall(this);
					Toast.makeText(this, R.string.downloaded_update_unavailable, Toast.LENGTH_LONG).show();
				}
				index++;
				performInstallation();
			} else {
				if (!getPackageManager().canRequestPackageInstalls()) {
					if (isInstallingClient()) {
						DownloadedUpdate.Entry entry = DownloadedUpdate.read(this);
						try { if (entry != null && entry.file.equals(file.getName())) DownloadedUpdate.beginInstallation(this, entry.id); }
						catch (IOException e) { android.util.Log.w("UpdateInstaller", "postpone_save_failed"); finish(); return; }
					}
					permissionRequest = true;
					try {
						unknownSources.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
								Uri.parse("package:" + getPackageName())));
					} catch (RuntimeException e) {
						permissionRequest = false;
						finish();
					}
				} else {
					try { installModel.start(new ArrayList<>(files), index, file, isInstallingClient()); }
					catch (RuntimeException e) {
						android.util.Log.w("UpdateInstaller", "start_failed type=" + e.getClass().getSimpleName());
						clearClientInstall(this);
						finish();
					}
				}
			}
		} else {
			android.util.Log.w("UpdateInstaller", "no_installable_files count=" + (files != null ? files.size() : -1));
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
			DownloadedUpdate.Entry entry = DownloadedUpdate.read(context);
			if (entry != null && entry.code <= BuildConfig.VERSION_CODE) {
				try { DownloadedUpdate.clear(context, entry.id); }
				catch (IOException e) { android.util.Log.w("UpdateInstaller", "installed_receipt_clear_failed"); }
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

	private static Connection activeConnection;
	private static boolean preparingRequest;
	private static volatile int requestGeneration;
	private static final java.util.concurrent.Executor REQUESTS = ConcurrentUtils.newSingleThreadPool(3000, "UpdateRequest", null);
	public static boolean isDownloadActive() { return preparingRequest || activeConnection != null; }

	public static Intent createInstallIntent(Context context, ArrayList<String> files) {
		return new Intent(context, UpdaterActivity.class)
				.putStringArrayListExtra(EXTRA_FILES, files)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
	}
	private static void installationReady(Context context, ArrayList<String> files) {
		try { notifyInstallReady(context, files); }
		catch (RuntimeException e) {
			android.util.Log.w("UpdateInstaller", "ready_notification_failed type=" + e.getClass().getSimpleName());
		}
		android.util.Log.i("UpdateInstaller", "download_ready count=" + files.size());
		if (!DownloadedUpdatePrompt.installIfVisible(files)) DownloadedUpdatePrompt.changed();
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
		private final ArrayList<String> installFiles;
		private final HashMap<String, Boolean> status = new HashMap<>();

		public Connection(Context context, List<DownloadService.DownloadItem> downloadItems, ArrayList<String> installFiles) {
			this.context = context;
			this.downloadItems = downloadItems;
			this.installFiles = installFiles;
			if (!context.bindService(new Intent(context, DownloadService.class), this, BIND_AUTO_CREATE)) {
				throw new IllegalStateException("Cannot connect to download service");
			}
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
			if (target != DataFile.Target.UPDATES || !StringUtils.isEmpty(path)) return;
			status.put(name, success);
			boolean successAll = true;
			for (DownloadService.DownloadItem downloadItem : downloadItems) {
				Boolean result = status.get(downloadItem.name);
				if (result == null) return;
				if (!result) successAll = false;
			}
			if (finish()) {
				if (successAll) installationReady(context, installFiles);
				else {
					android.util.Log.w("UpdateInstaller", "download_queue_failed");
					DownloadedUpdatePrompt.changed();
				}
			}
		}

		@Override
		public void onCleanup() {
			finish();
		}

	}

	public static class Request {
		public final String extensionName;
		public final String versionName;
		public final Uri uri;
		public final byte[] sha256sum;
		public final ChanManager.Fingerprints checkFingerprints;
		public final long versionCode, length;

		public Request(String extensionName, String versionName, Uri uri,
				byte[] sha256sum, ChanManager.Fingerprints checkFingerprints) {
			this(extensionName, versionName, uri, sha256sum, checkFingerprints, -1, -1);
		}
		public Request(String extensionName, String versionName, Uri uri,
				byte[] sha256sum, ChanManager.Fingerprints checkFingerprints, long versionCode, long length) {
			this.extensionName = extensionName;
			this.versionName = versionName;
			this.uri = uri;
			this.sha256sum = sha256sum;
			this.checkFingerprints = checkFingerprints;
			this.versionCode = versionCode; this.length = length;
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
		Request clientRequest = null;
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
				clientRequest = request;
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
			// Do not attach a second owner to a file the service is still writing.
			android.util.Log.i("UpdateInstaller", "download_already_active");
			return;
		}
		final int generation = ++requestGeneration;
		final Request client = clientRequest;
		final DownloadService.DownloadItem clientItem = clientDownloadItem;
		Context context = MainApplication.getInstance();
		preparingRequest = true;
		REQUESTS.execute(() -> {
			if (generation != requestGeneration) return;
			DownloadedUpdate.Check cached = null;
			boolean failed = false;
			try {
				if (client != null) cached = DownloadedUpdate.check(context, DownloadedUpdate.remember(context, client));
			} catch (IOException | RuntimeException e) {
				failed = true;
				android.util.Log.w("UpdateInstaller", "receipt_save_failed type=" + e.getClass().getSimpleName());
			}
			final boolean failure = failed;
			final DownloadedUpdate.Check check = cached;
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				if (generation != requestGeneration) return;
				preparingRequest = false;
				if (failure) { Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_LONG).show(); return; }
				ArrayList<String> files = new ArrayList<>();
				for (DownloadService.DownloadItem item : downloadItems) files.add(item.name);
				if (check == DownloadedUpdate.Check.READY || check == DownloadedUpdate.Check.INSTALLED) {
					downloadItems.remove(clientItem);
					if (check == DownloadedUpdate.Check.INSTALLED) files.remove(clientItem.name);
					android.util.Log.i("UpdateInstaller", "cached_client result=" + check);
				}
				if (downloadItems.isEmpty()) {
					if (!files.isEmpty()) installationReady(context, files);
				} else {
					try { activeConnection = new Connection(context, downloadItems, files); }
					catch (RuntimeException e) {
						android.util.Log.w("UpdateInstaller", "download_bind_failed type=" + e.getClass().getSimpleName());
						Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_LONG).show();
					}
				}
			});
		});
	}
}
