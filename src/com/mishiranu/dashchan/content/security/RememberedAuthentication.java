package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/** A boot-bound receipt inside SecurityStore's atomic, no-backup record. Contains no PIN. */
final class RememberedAuthentication {
	static final String KEY = "remembered_authentication";
	final long authenticatedAt;
	final int minutes;
	private RememberedAuthentication(long authenticatedAt, int minutes) {
		this.authenticatedAt = authenticatedAt; this.minutes = minutes;
	}

	private static String alias(Context context) throws Exception {
		// Reuse sandbox namespacing, NOT the PIN verifier key. Revocation must not break PINs.
		return SecurityStore.pinAlias(context) + ".reauthentication";
	}
	private static SecretKey key(Context context, boolean create) throws Exception {
		String alias = alias(context);
		KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
		if (!store.containsAlias(alias)) {
			if (!create) throw new IOException("Remembered authentication key unavailable");
			KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
			generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
					.setDigests(KeyProperties.DIGEST_SHA256).build());
			generator.generateKey();
		}
		return (SecretKey) store.getKey(alias, null);
	}
	static void revoke(Context context) throws IOException {
		try {
			KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
			store.deleteEntry(alias(context));
		} catch (Exception e) { throw new IOException("Cannot revoke remembered authentication", e); }
	}
	private static int boot(Context context) {
		return android.provider.Settings.Global.getInt(context.getContentResolver(),
				android.provider.Settings.Global.BOOT_COUNT, -1);
	}
	private static byte[] payload(JSONObject state, long at, int minutes, int boot) throws Exception {
		// Domain separation and binding to the exact installation's current credentials/profile.
		return new JSONArray().put("slooop-reauthentication-v1").put(at).put(minutes).put(boot)
				.put(state.optString("primary")).put(state.optString("additional"))
				.put(state.optString(AuthenticationPolicy.KEY)).put(state.optBoolean("biometric"))
				.put(state.optString("biometric_alias")).put(state.optLong("profile_generation"))
				.toString().getBytes(StandardCharsets.UTF_8);
	}
	private static byte[] sign(SecretKey key, byte[] payload) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key); return mac.doFinal(payload);
	}
	static void save(Context context, JSONObject state, long authenticatedAt) throws Exception {
		state.remove(KEY);
		int minutes = ReauthenticationPolicy.normalize(state.optInt(ReauthenticationPolicy.KEY, 0));
		int boot = boot(context);
		if (SecurityStore.authenticationMode(state) == null || state.optBoolean("reset_pending")
				|| !ReauthenticationPolicy.canRestore(authenticatedAt, minutes, boot, boot, SystemClock.elapsedRealtime())) {
			revoke(context); return;
		}
		byte[] tag = sign(key(context, true), payload(state, authenticatedAt, minutes, boot));
		state.put(KEY, new JSONObject().put("version", 1).put("at", authenticatedAt).put("minutes", minutes)
				.put("boot", boot).put("tag", Base64.encodeToString(tag, Base64.NO_WRAP)));
	}
	private static long integer(JSONObject object, String name) throws Exception {
		Object value = object.get(name);
		if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("Invalid authentication receipt");
		return ((Number) value).longValue();
	}
	static RememberedAuthentication read(Context context, JSONObject state) {
		try {
			if (SecurityStore.authenticationMode(state) == null || state.optBoolean("reset_pending")) return null;
			JSONObject receipt = state.getJSONObject(KEY);
			if (receipt.length() != 5 || integer(receipt, "version") != 1) return null;
			long at = integer(receipt, "at"), minutes = integer(receipt, "minutes"), savedBoot = integer(receipt, "boot");
			if (minutes != ReauthenticationPolicy.normalize(state.optInt(ReauthenticationPolicy.KEY, 0))
					|| savedBoot < 0 || savedBoot > Integer.MAX_VALUE
					|| !ReauthenticationPolicy.canRestore(at, (int) minutes, (int) savedBoot, boot(context), SystemClock.elapsedRealtime())) return null;
			Object encoded = receipt.get("tag");
			if (!(encoded instanceof String) || ((String) encoded).length() != 44) return null;
			byte[] tag = Base64.decode((String) encoded, Base64.NO_WRAP);
			if (tag.length != 32 || !MessageDigest.isEqual(tag, sign(key(context, false), payload(state, at, (int) minutes, (int) savedBoot)))) return null;
			return new RememberedAuthentication(at, (int) minutes);
		} catch (Exception e) { return null; } // Missing, damaged or unverifiable receipts never authorize.
	}
}
