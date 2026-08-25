package com.mishiranu.dashchan.content.push;

import android.content.Context;
import android.content.SharedPreferences;
import chan.util.StringUtils;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class ReplyPushPrivateStore {
	private static final String NAME = "reply_push_private";
	private static final String KEY_INSTALLATION_ID = "installation_id";
	private static final String KEY_INSTALLATION_SECRET = "installation_secret";
	private static final String KEY_FIREBASE_REGISTRATION_ID = "firebase_registration_id";
	private static final String KEY_CONSENT_ACCEPTED = "consent_accepted";
	private static final String KEY_WATCH_IDS = "watch_ids";
	private static final String KEY_REJECTED_WATCH_IDS = "rejected_watch_ids";
	private static final String KEY_RESET_PENDING = "reset_pending";
	private static final String KEY_RESET_FAILED = "reset_failed";
	private static final String KEY_RESET_SERVER_DELETED = "reset_server_deleted";
	private static final String KEY_LAST_RESET_SUCCESS = "last_reset_success";

	private ReplyPushPrivateStore() {}

	private static SharedPreferences getPreferences(Context context) {
		return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
	}

	public static synchronized String getInstallationId(Context context) {
		String installationId = getPreferences(context).getString(KEY_INSTALLATION_ID, null);
		return ReplyPushContract.isInstallationId(installationId) ? installationId : null;
	}

	public static synchronized void setInstallationId(Context context, String installationId) {
		if (installationId != null && !ReplyPushContract.isInstallationId(installationId)) {
			throw new IllegalArgumentException();
		}
		SharedPreferences preferences = getPreferences(context);
		String currentInstallationId = preferences.getString(KEY_INSTALLATION_ID, null);
		if (java.util.Objects.equals(currentInstallationId, installationId)) {
			return;
		}
		SharedPreferences.Editor editor = preferences.edit().remove(KEY_INSTALLATION_SECRET)
				.remove(KEY_WATCH_IDS).remove(KEY_REJECTED_WATCH_IDS);
		if (installationId != null) {
			editor.putString(KEY_INSTALLATION_ID, installationId);
		} else {
			editor.remove(KEY_INSTALLATION_ID);
		}
		editor.commit();
	}

	public static synchronized boolean hasConsent(Context context) {
		return getPreferences(context).getBoolean(KEY_CONSENT_ACCEPTED, false);
	}

	public static synchronized void setConsent(Context context, boolean accepted) {
		SharedPreferences.Editor editor = getPreferences(context).edit();
		if (accepted) {
			editor.putBoolean(KEY_CONSENT_ACCEPTED, true);
		} else {
			editor.remove(KEY_CONSENT_ACCEPTED);
		}
		editor.commit();
	}

	public static synchronized String getSecret(Context context, String installationId) {
		if (StringUtils.isEmpty(installationId)) {
			return null;
		}
		SharedPreferences preferences = getPreferences(context);
		return installationId.equals(preferences.getString(KEY_INSTALLATION_ID, null))
				? preferences.getString(KEY_INSTALLATION_SECRET, null) : null;
	}

	public static synchronized void putSecret(Context context, String installationId, String secret) {
		if (!ReplyPushContract.isInstallationId(installationId) || StringUtils.isEmpty(secret)) {
			throw new IllegalArgumentException();
		}
		getPreferences(context).edit()
				.putString(KEY_INSTALLATION_ID, installationId)
				.putString(KEY_INSTALLATION_SECRET, secret)
				.apply();
	}

	public static synchronized String getFirebaseRegistrationId(Context context) {
		return getPreferences(context).getString(KEY_FIREBASE_REGISTRATION_ID, null);
	}

	public static synchronized void setFirebaseRegistrationId(Context context, String registrationId) {
		SharedPreferences.Editor editor = getPreferences(context).edit();
		if (StringUtils.isEmpty(registrationId)) {
			editor.remove(KEY_FIREBASE_REGISTRATION_ID);
		} else {
			editor.putString(KEY_FIREBASE_REGISTRATION_ID, registrationId);
		}
		editor.apply();
	}

	public static synchronized Set<String> getWatchIds(Context context, String installationId) {
		if (installationId == null || !installationId.equals(
				getPreferences(context).getString(KEY_INSTALLATION_ID, null))) {
			return Collections.emptySet();
		}
		return new HashSet<>(getPreferences(context).getStringSet(KEY_WATCH_IDS,
				Collections.emptySet()));
	}

	public static synchronized void setWatchIds(Context context, String installationId,
			Set<String> watchIds) {
		SharedPreferences preferences = getPreferences(context);
		if (installationId != null && installationId.equals(
				preferences.getString(KEY_INSTALLATION_ID, null))) {
			preferences.edit().putStringSet(KEY_WATCH_IDS, new HashSet<>(watchIds)).apply();
		}
	}

	public static synchronized Set<String> getRejectedWatchIds(Context context, String installationId) {
		if (installationId == null || !installationId.equals(
				getPreferences(context).getString(KEY_INSTALLATION_ID, null))) {
			return Collections.emptySet();
		}
		return new HashSet<>(getPreferences(context).getStringSet(KEY_REJECTED_WATCH_IDS,
				Collections.emptySet()));
	}

	public static synchronized void setRejectedWatchIds(Context context, String installationId,
			Set<String> watchIds) {
		SharedPreferences preferences = getPreferences(context);
		if (installationId != null && installationId.equals(
				preferences.getString(KEY_INSTALLATION_ID, null))) {
			preferences.edit().putStringSet(KEY_REJECTED_WATCH_IDS, new HashSet<>(watchIds)).apply();
		}
	}

	public static synchronized void clearInstallation(Context context, String installationId) {
		SharedPreferences preferences = getPreferences(context);
		if (installationId == null || installationId.equals(
				preferences.getString(KEY_INSTALLATION_ID, null))) {
			preferences.edit().remove(KEY_INSTALLATION_ID).remove(KEY_INSTALLATION_SECRET)
					.remove(KEY_WATCH_IDS).remove(KEY_REJECTED_WATCH_IDS).apply();
		}
	}

	public static synchronized boolean beginIdentityReset(Context context) {
		SharedPreferences preferences = getPreferences(context);
		if (preferences.getBoolean(KEY_RESET_PENDING, false)
				|| getIdentityResetCooldownRemaining(context) > 0L) {
			return false;
		}
		return preferences.edit().putBoolean(KEY_RESET_PENDING, true)
				.remove(KEY_RESET_FAILED).remove(KEY_RESET_SERVER_DELETED).commit();
	}

	public static synchronized boolean isIdentityResetPending(Context context) {
		return getPreferences(context).getBoolean(KEY_RESET_PENDING, false);
	}

	public static synchronized boolean didIdentityResetFail(Context context) {
		return getPreferences(context).getBoolean(KEY_RESET_FAILED, false);
	}

	public static synchronized long getIdentityResetCooldownRemaining(Context context) {
		long lastSuccess = getPreferences(context).getLong(KEY_LAST_RESET_SUCCESS, 0L);
		return ReplyPushContract.getCooldownRemaining(System.currentTimeMillis(), lastSuccess,
				ReplyPushContract.IDENTITY_RESET_COOLDOWN_MILLIS);
	}

	public static synchronized boolean isIdentityResetServerDeleted(Context context) {
		return getPreferences(context).getBoolean(KEY_RESET_SERVER_DELETED, false);
	}

	public static synchronized void markIdentityResetServerDeleted(Context context) {
		getPreferences(context).edit().putBoolean(KEY_RESET_SERVER_DELETED, true).commit();
	}

	public static synchronized void markIdentityResetSucceeded(Context context) {
		getPreferences(context).edit().remove(KEY_RESET_PENDING).remove(KEY_RESET_FAILED)
				.remove(KEY_RESET_SERVER_DELETED)
				.putLong(KEY_LAST_RESET_SUCCESS, System.currentTimeMillis()).commit();
	}

	public static synchronized void markIdentityResetFailed(Context context) {
		getPreferences(context).edit().remove(KEY_RESET_PENDING)
				.remove(KEY_RESET_SERVER_DELETED).putBoolean(KEY_RESET_FAILED, true).commit();
	}

	public static synchronized void clearRegistration(Context context) {
		getPreferences(context).edit().remove(KEY_INSTALLATION_ID).remove(KEY_INSTALLATION_SECRET)
				.remove(KEY_FIREBASE_REGISTRATION_ID).remove(KEY_WATCH_IDS)
				.remove(KEY_REJECTED_WATCH_IDS).commit();
	}

	public static synchronized void clearAll(Context context) {
		getPreferences(context).edit().clear().apply();
	}
}
