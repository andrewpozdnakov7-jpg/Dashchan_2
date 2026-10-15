package com.mishiranu.dashchan.content.security;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import com.mishiranu.dashchan.content.MainApplication;

/** Recovery starts before ContentProviders and framework saved-state restoration. */
public final class SecurityComponentFactory extends android.app.AppComponentFactory {
	private final java.util.function.BooleanSupplier recoveryGate;
	public SecurityComponentFactory() { this(SecurityComponentFactory::recovery); }
	SecurityComponentFactory(java.util.function.BooleanSupplier recoveryGate) { this.recoveryGate = recoveryGate; }
	private static boolean recovery() {
		if (android.app.Application.getProcessName().endsWith(":security")) return false;
		MainApplication application = MainApplication.getInstance();
		return application != null && (application.isSecurityRecoveryProcess() || PrivacyReset.requiresRecovery(application));
	}
	@Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		return recoveryGate.getAsBoolean() ? new RecoveryRedirectActivity() : super.instantiateActivity(loader, name, intent);
	}
	@Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		return recoveryGate.getAsBoolean() ? new RecoveryService() : super.instantiateService(loader, name, intent);
	}
	@Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		return recoveryGate.getAsBoolean() ? new RecoveryReceiver() : super.instantiateReceiver(loader, name, intent);
	}
	@Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		return recoveryGate.getAsBoolean() ? new RecoveryProvider() : super.instantiateProvider(loader, name);
	}
	public static final class RecoveryRedirectActivity extends Activity {
		@Override protected void onCreate(Bundle ignoredOldProfileState) {
			super.onCreate(null);
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
			startActivity(new Intent(this, PrivacyResetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
			finishAndRemoveTask();
		}
	}
	public static final class RecoveryService extends Service {
		@Override public int onStartCommand(Intent intent, int flags, int id) { stopSelf(); return START_NOT_STICKY; }
		@Override public IBinder onBind(Intent intent) { return null; }
	}
	public static final class RecoveryReceiver extends BroadcastReceiver {
		@Override public void onReceive(Context context, Intent intent) { /* Old-profile work is not dispatched. */ }
	}
	public static final class RecoveryProvider extends ContentProvider {
		@Override public boolean onCreate() { return true; }
		@Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
		@Override public String getType(Uri uri) { return null; }
		@Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Profile cleanup pending"); }
		@Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("Profile cleanup pending"); }
		@Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("Profile cleanup pending"); }
	}
}
