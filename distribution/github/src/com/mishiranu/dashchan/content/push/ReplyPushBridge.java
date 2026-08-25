package com.mishiranu.dashchan.content.push;

import android.content.Context;

public final class ReplyPushBridge {
	private ReplyPushBridge() {}

	public static boolean isSupported() {
		return true;
	}

	public static boolean isConfigured() {
		Context context = com.mishiranu.dashchan.content.MainApplication.getInstance();
		String installationId = getInstallationId(context);
		return ReplyPushContract.isInstallationId(installationId)
				&& ReplyPushPrivateStore.getSecret(context, installationId) != null;
	}

	public static boolean hasConsent(Context context) {
		return ReplyPushPrivateStore.hasConsent(context.getApplicationContext());
	}

	public static String getInstallationId(Context context) {
		Context applicationContext = context.getApplicationContext();
		String installationId = ReplyPushPrivateStore.getInstallationId(applicationContext);
		if (installationId == null) {
			String legacyInstallationId = com.mishiranu.dashchan.content.Preferences
					.takeLegacyReplyPushInstallationId();
			if (ReplyPushContract.isInstallationId(legacyInstallationId)) {
				ReplyPushPrivateStore.setInstallationId(applicationContext, legacyInstallationId);
				installationId = legacyInstallationId;
			}
		}
		return installationId;
	}

	public static void setInstallationId(Context context, String installationId) {
		if (installationId == null) {
			com.mishiranu.dashchan.content.Preferences.takeLegacyReplyPushInstallationId();
		}
		ReplyPushPrivateStore.setInstallationId(context.getApplicationContext(), installationId);
	}

	public static void setEnabled(Context context, boolean enabled, String installationId) {
		Context applicationContext = context.getApplicationContext();
		if (enabled) {
			ReplyPushPrivateStore.setConsent(applicationContext, true);
			try {
				ReplyPushFirebase.setAutoInitEnabled(applicationContext, true);
			} catch (RuntimeException ignored) {
				// The worker retries initialization without blocking the settings screen.
			}
			ReplyPushSyncWorker.enqueueSync(applicationContext);
			ReplyPushSyncWorker.updatePeriodicSchedule(applicationContext, true);
		} else {
			ReplyPushPrivateStore.setConsent(applicationContext, false);
			try {
				ReplyPushFirebase.setAutoInitEnabled(applicationContext, false);
			} catch (RuntimeException ignored) {
				// Deletion work still removes the server registration and retries local cleanup.
			}
			ReplyPushSyncWorker.enqueueDelete(applicationContext, installationId);
		}
	}

	public static void registerWatch(Context context, String installationId, String chanName,
			String boardName, String threadNumber, String postNumber) {
		ReplyPushSyncWorker.enqueueSync(context.getApplicationContext());
	}

	public static boolean resetIdentity(Context context, String installationId) {
		Context applicationContext = context.getApplicationContext();
		if (!ReplyPushContract.isInstallationId(installationId)
				|| ReplyPushPrivateStore.getSecret(applicationContext, installationId) == null
				|| !ReplyPushPrivateStore.beginIdentityReset(applicationContext)) {
			return false;
		}
		try {
			ReplyPushSyncWorker.enqueueIdentityReset(applicationContext, installationId);
			return true;
		} catch (RuntimeException e) {
			ReplyPushPrivateStore.markIdentityResetFailed(applicationContext);
			return false;
		}
	}

	public static boolean isIdentityResetPending(Context context) {
		return ReplyPushPrivateStore.isIdentityResetPending(context.getApplicationContext());
	}

	public static boolean didIdentityResetFail(Context context) {
		return ReplyPushPrivateStore.didIdentityResetFail(context.getApplicationContext());
	}

	public static long getIdentityResetCooldownRemaining(Context context) {
		return ReplyPushPrivateStore.getIdentityResetCooldownRemaining(context.getApplicationContext());
	}
}
