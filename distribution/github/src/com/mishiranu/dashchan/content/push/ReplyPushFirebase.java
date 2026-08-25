package com.mishiranu.dashchan.content.push;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.concurrent.TimeUnit;

final class ReplyPushFirebase {
	private ReplyPushFirebase() {}

	private static synchronized FirebaseApp initialize(Context context) {
		Context applicationContext = context.getApplicationContext();
		for (FirebaseApp app : FirebaseApp.getApps(applicationContext)) {
			if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
				return app;
			}
		}
		FirebaseApp app = FirebaseApp.initializeApp(applicationContext);
		if (app == null) {
			throw new IllegalStateException("Firebase client configuration is unavailable");
		}
		return app;
	}

	public static FirebaseMessaging getMessaging(Context context) {
		initialize(context);
		return FirebaseMessaging.getInstance();
	}

	public static void setAutoInitEnabled(Context context, boolean enabled) {
		getMessaging(context).setAutoInitEnabled(enabled);
	}

	public static void awaitRegistration(Context context) throws Exception {
		Tasks.await(getMessaging(context).register(), 30L, TimeUnit.SECONDS);
	}

	public static boolean unregister(Context context) {
		try {
			Tasks.await(getMessaging(context).unregister(), 30L, TimeUnit.SECONDS);
			return true;
		} catch (Exception ignored) {
			// A later disable can retry. Never expose the registration ID in diagnostics.
			return false;
		}
	}
}
