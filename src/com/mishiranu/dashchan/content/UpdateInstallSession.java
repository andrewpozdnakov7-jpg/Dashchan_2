package com.mishiranu.dashchan.content;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;

/** One durable, user-confirmed installation at a time. Never retains an Activity. */
public final class UpdateInstallSession {
	static final int PREPARING = -100;
	static final int COMMITTED = -101;
	static final String EXTRA_TOKEN = "install_token";
	static final String EXTRA_CONFIRMATION = "install_confirmation";
	private static final String PREFERENCES = "update_install_session";
	private static final String KEY_SESSION = "session";
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static WeakReference<Model> activeModel = new WeakReference<>(null);

	private static SharedPreferences preferences(Context context) {
		return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
	}

	static String token(Context context) { return preferences(context).getString("token", null); }
	static int status(Context context) { return preferences(context).getInt("status", PREPARING); }
	static int index(Context context) { return preferences(context).getInt("index", 0); }

	static ArrayList<String> files(Context context) {
		ArrayList<String> result = new ArrayList<>();
		try {
			JSONArray array = new JSONArray(preferences(context).getString("files", "[]"));
			for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
		} catch (JSONException e) {
			// Private metadata only; malformed state must not install a different file.
			result.clear();
		}
		return result;
	}

	private static void signal() {
		MAIN.post(() -> {
			Model model = activeModel.get();
			if (model != null) model.changed.setValue(Boolean.TRUE);
		});
	}

	static synchronized void clear(Context context, String expectedToken) {
		if (expectedToken == null || !expectedToken.equals(token(context))) return;
		preferences(context).edit().clear().commit();
		((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
				.cancel(expectedToken, C.NOTIFICATION_ID_INSTALL_CONFIRMATION);
		Intent result = new Intent(context, StatusReceiver.class)
				.setData(Uri.parse("slooop-install:" + expectedToken));
		int flags = PendingIntent.FLAG_NO_CREATE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
		PendingIntent callback = PendingIntent.getBroadcast(context, 0, result, flags);
		if (callback != null) callback.cancel();
		Intent resume = new Intent(context, UpdaterActivity.class)
				.setData(Uri.parse("slooop-install:" + expectedToken));
		PendingIntent notification = PendingIntent.getActivity(context, 0, resume,
				PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
		if (notification != null) notification.cancel();
	}

	private static void abandon(Context context, int sessionId) {
		if (sessionId < 0) return;
		try { context.getPackageManager().getPackageInstaller().abandonSession(sessionId); }
		catch (RuntimeException e) {
			android.util.Log.w("UpdateInstaller", "abandon_failed type=" + e.getClass().getSimpleName());
		}
	}

	static synchronized void cancel(Context context, String expectedToken) {
		if (expectedToken == null || !expectedToken.equals(token(context))) return;
		abandon(context, preferences(context).getInt(KEY_SESSION, -1));
		clear(context, expectedToken);
	}

	public static final class Model extends ViewModel {
		final MutableLiveData<Boolean> changed = new MutableLiveData<>();
		Intent confirmation;
		boolean foreground;
		private volatile boolean cancelled;
		private volatile boolean preparing;
		private String ownedToken;

		public Model() { activeModel = new WeakReference<>(this); }

		void start(ArrayList<String> files, int index, File file, boolean client) {
			Context context = MainApplication.getInstance();
			final String operation = UUID.randomUUID().toString();
			synchronized (UpdateInstallSession.class) {
				if (token(context) != null) return;
				if (!preferences(context).edit().putString("token", operation)
						.putString("files", new JSONArray(files).toString()).putInt("index", index)
						.putInt(KEY_SESSION, -1).putInt("status", PREPARING).commit()) {
					throw new IllegalStateException("Cannot persist installation state");
				}
				preparing = true;
				ownedToken = operation;
			}
			ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
				PackageInstaller installer = context.getPackageManager().getPackageInstaller();
				int sessionId = -1;
				boolean committed = false;
				try {
					PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
							PackageInstaller.SessionParams.MODE_FULL_INSTALL);
					params.setSize(file.length());
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
						params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
					}
					sessionId = installer.createSession(params);
					synchronized (UpdateInstallSession.class) {
						if (cancelled || !operation.equals(token(context))) return;
						if (!preferences(context).edit().putInt(KEY_SESSION, sessionId).commit()) {
							throw new IllegalStateException("Cannot persist installation session");
						}
					}
					try (PackageInstaller.Session session = installer.openSession(sessionId);
							FileInputStream input = new FileInputStream(file);
							OutputStream output = session.openWrite("base.apk", 0, file.length())) {
						byte[] buffer = new byte[65536];
						int count;
						while ((count = input.read(buffer)) != -1) {
							if (cancelled) return;
							output.write(buffer, 0, count);
						}
						session.fsync(output);
					}
					try (PackageInstaller.Session session = installer.openSession(sessionId)) {
						Intent result = new Intent(context, StatusReceiver.class)
								.setData(Uri.parse("slooop-install:" + operation))
								.putExtra(EXTRA_TOKEN, operation);
						int flags = PendingIntent.FLAG_UPDATE_CURRENT;
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
						PendingIntent callback = PendingIntent.getBroadcast(context, 0, result, flags);
						synchronized (UpdateInstallSession.class) {
							if (cancelled || !operation.equals(token(context))) return;
							if (!preferences(context).edit().putInt("status", COMMITTED).commit()) {
								throw new IllegalStateException("Cannot persist committed installation");
							}
							if (client) UpdaterActivity.markClientInstall(context);
							session.commit(callback.getIntentSender());
							committed = true;
						}
					}
				} catch (Exception e) {
					android.util.Log.w("UpdateInstaller", "prepare_failed type=" + e.getClass().getSimpleName());
					synchronized (UpdateInstallSession.class) {
						if (operation.equals(token(context))) {
							preferences(context).edit().putInt("status", PackageInstaller.STATUS_FAILURE).commit();
							if (client) UpdaterActivity.clearClientInstall(context);
						}
					}
				} finally {
					if (!committed) abandon(context, sessionId);
					synchronized (UpdateInstallSession.class) {
						if (operation.equals(ownedToken)) preparing = false;
					}
					signal();
				}
			});
		}

		void recoverPreparation() {
			// A retained model owns a live copy. After process death PREPARING is uncommitted.
			if (preparing) return;
			Context context = MainApplication.getInstance();
			synchronized (UpdateInstallSession.class) {
				String operation = token(context);
				if (operation != null) {
					int status = status(context);
					int sessionId = preferences(context).getInt(KEY_SESSION, -1);
					PackageInstaller.SessionInfo info = status == COMMITTED ? context.getPackageManager()
							.getPackageInstaller().getSessionInfo(sessionId) : null;
					if (status == PREPARING || status == COMMITTED && info != null && !info.isSealed()) {
						abandon(context, sessionId);
						clear(context, operation);
						UpdaterActivity.clearClientInstall(context);
					} else if (status == COMMITTED && info == null) {
						// No live session and no durable final callback: do not assume success.
						preferences(context).edit().putInt("status", PackageInstaller.STATUS_FAILURE).commit();
					}
				}
			}
		}

		@Override protected void onCleared() {
			cancelled = true;
			Context context = MainApplication.getInstance();
			synchronized (UpdateInstallSession.class) {
				if (ownedToken != null && ownedToken.equals(token(context)) && status(context) == PREPARING) {
					abandon(context, preferences(context).getInt(KEY_SESSION, -1));
					clear(context, token(context));
				}
				if (activeModel.get() == this) activeModel.clear();
			}
		}
	}

	public static class StatusReceiver extends BroadcastReceiver {
		@Override public void onReceive(Context context, Intent intent) {
			String operation = intent.getStringExtra(EXTRA_TOKEN);
			int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
			Intent confirmation = null;
			boolean foreground = false;
			synchronized (UpdateInstallSession.class) {
				if (operation == null || !operation.equals(token(context))
						|| intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
						!= preferences(context).getInt(KEY_SESSION, -2)
						|| status(context) >= PackageInstaller.STATUS_SUCCESS) return;
				if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
					confirmation = AndroidUtils.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
					if (confirmation == null) result = PackageInstaller.STATUS_FAILURE;
				}
				preferences(context).edit().putInt("status", result).commit();
				Model model = activeModel.get();
				if (model != null) {
					model.confirmation = confirmation;
					foreground = model.foreground;
				}
				if (result != PackageInstaller.STATUS_PENDING_USER_ACTION) {
					((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
							.cancel(operation, C.NOTIFICATION_ID_INSTALL_CONFIRMATION);
					if (result != PackageInstaller.STATUS_SUCCESS) UpdaterActivity.clearClientInstall(context);
				}
			}
			try {
				if (confirmation != null && !foreground) notifyConfirmation(context, operation, confirmation);
			} catch (RuntimeException e) {
				android.util.Log.w("UpdateInstaller", "notification_failed type=" + e.getClass().getSimpleName());
			} finally {
				// Never start an Activity from a background installation callback.
				signal();
			}
		}
	}

	private static void notifyConfirmation(Context context, String operation, Intent confirmation) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(
				Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
		NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.createNotificationChannel(AndroidUtils.createHeadsUpNotificationChannel(
				C.NOTIFICATION_CHANNEL_UPDATES, context.getString(R.string.updates)));
		Intent resume = UpdaterActivity.createInstallIntent(context, files(context))
				.setData(Uri.parse("slooop-install:" + operation))
				.putExtra(EXTRA_TOKEN, operation).putExtra(EXTRA_CONFIRMATION, confirmation);
		PendingIntent pending = PendingIntent.getActivity(context, 0, resume,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		manager.notify(operation, C.NOTIFICATION_ID_INSTALL_CONFIRMATION,
				new NotificationCompat.Builder(context, C.NOTIFICATION_CHANNEL_UPDATES)
						.setSmallIcon(android.R.drawable.stat_sys_download_done)
						.setContentTitle(context.getString(R.string.update_downloaded))
						.setContentText(context.getString(R.string.tap_to_install_update__sentence))
						.setContentIntent(pending).setAutoCancel(true).build());
	}
}
