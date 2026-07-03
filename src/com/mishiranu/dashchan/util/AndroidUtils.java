package com.mishiranu.dashchan.util;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.RequiresApi;
import chan.util.StringUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class AndroidUtils {
	public static final boolean IS_MIUI;

	static {
		boolean isMiui = false;
		try {
			@SuppressLint("PrivateApi")
			Method getProperty = Class.forName("android.os.SystemProperties")
					.getMethod("get", String.class, String.class);
			isMiui = !StringUtils.isEmpty((String) getProperty.invoke(null, "ro.miui.ui.version.name", ""));
		} catch (Exception e) {
			// Ignore exception
		}
		IS_MIUI = isMiui;
	}

	public interface OnReceiveListener {
		void onReceive(BroadcastReceiver receiver, Context context, Intent intent);
	}

	public static BroadcastReceiver createReceiver(OnReceiveListener listener) {
		return new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				listener.onReceive(this, context, intent);
			}
		};
	}

	public static void startAnyService(Context context, Intent intent) {
		context.startForegroundService(intent);
	}

	public static PendingIntent getAnyServicePendingIntent(Context context, int requestCode, Intent intent, int flags) {
		return PendingIntent.getForegroundService(context, requestCode, intent, flags);
	}

	public static String getApplicationLabel(Context context) {
		return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
	}

	public static <T extends Parcelable> T getParcelable(Bundle bundle, String key, Class<T> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return bundle.getParcelable(key, clazz);
		} else {
			@SuppressWarnings("deprecation")
			T result = bundle.getParcelable(key);
			return result;
		}
	}

	public static <T extends Parcelable> ArrayList<T> getParcelableArrayList
			(Bundle bundle, String key, Class<? extends T> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return bundle.getParcelableArrayList(key, clazz);
		} else {
			@SuppressWarnings("deprecation")
			ArrayList<T> result = bundle.getParcelableArrayList(key);
			return result;
		}
	}

	public static Parcelable[] getParcelableArray(Bundle bundle, String key, Class<? extends Parcelable> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return bundle.getParcelableArray(key, clazz);
		} else {
			@SuppressWarnings("deprecation")
			Parcelable[] result = bundle.getParcelableArray(key);
			return result;
		}
	}

	public static <T extends Parcelable> T getParcelableExtra(Intent intent, String key, Class<T> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return intent.getParcelableExtra(key, clazz);
		} else {
			@SuppressWarnings("deprecation")
			T result = intent.getParcelableExtra(key);
			return result;
		}
	}

	public static <T extends Parcelable> ArrayList<T> getParcelableArrayListExtra
			(Intent intent, String key, Class<? extends T> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return intent.getParcelableArrayListExtra(key, clazz);
		} else {
			@SuppressWarnings("deprecation")
			ArrayList<T> result = intent.getParcelableArrayListExtra(key);
			return result;
		}
	}

	public static <T extends Parcelable> T readParcelable(Parcel source, ClassLoader loader, Class<T> clazz) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			return source.readParcelable(loader, clazz);
		} else {
			@SuppressWarnings("deprecation")
			T result = source.readParcelable(loader);
			return result;
		}
	}

	@SuppressWarnings("deprecation")
	public static void stopForegroundRemove(Service service) {
		// Keep the targetSdk 30 foreground-service lifecycle semantics for this cleanup pass.
		service.stopForeground(true);
	}

	@RequiresApi(Build.VERSION_CODES.O)
	public static NotificationChannel createHeadsUpNotificationChannel(String id, CharSequence name) {
		NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
		channel.setSound(null, null);
		channel.setVibrationPattern(new long[] {0});
		return channel;
	}

	@SuppressLint("NewApi")
	public static boolean hasCallbacks(Handler handler, Runnable runnable) {
		return handler.hasCallbacks(runnable);
	}
}
