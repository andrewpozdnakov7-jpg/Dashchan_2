package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/** Cross-process, atomic state; outside Android and manual application backups. */
public final class SecurityStore {
	public static final String ALLOW_SCREENSHOTS = "allow_screenshots";
	private static final java.util.concurrent.locks.ReentrantLock MONITOR = new java.util.concurrent.locks.ReentrantLock();
	private static final String PIN_KEY = "slooop.app-lock.pin.v1";
	public interface Change<T> { T apply(JSONObject state) throws Exception; }
	public enum Result { PRIMARY, ADDITIONAL, FAILED, WAIT }
	private SecurityStore() {}

	public static File directory(Context context) {
		return new File(context.getNoBackupFilesDir(), "app-security-v1");
	}

	public static <T> T update(Context context, Change<T> change) throws IOException {
		return authorizedUpdate(context, () -> true, change);
	}

	public static <T> T authorizedUpdate(Context context, java.util.function.BooleanSupplier authorization,
			Change<T> change) throws IOException {
		boolean acquired;
		try { acquired = MONITOR.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); SecuritySessionProvider.revoke(context); throw new IOException("Security operation interrupted"); }
		if (!acquired) { SecuritySessionProvider.revoke(context); throw new IOException("Security storage busy"); }
		try {
			File directory = directory(context);
			if (!directory.isDirectory() && !directory.mkdirs()) {
				SecuritySessionProvider.revoke(context);
				throw new IOException("Security storage unavailable");
			}
			AtomicFile file = new AtomicFile(new File(directory, "state.json"));
			try (RandomAccessFile mutex = new RandomAccessFile(new File(directory, "state.lock"), "rw");
					FileLock lock = acquireFileLock(mutex)) {
				if (!lock.isValid()) throw new IOException("Security lock unavailable");
				JSONObject state;
				byte[] previousBytes;
				// Never turn a corrupt security record into a disabled lock.
				if (file.getBaseFile().exists() || new File(directory, "state.json.bak").exists()) {
					previousBytes = SecurityStateCodec.read(file, SecurityStateCodec.LEGACY_READ_BYTES);
					state = SecurityStateCodec.decode(previousBytes);
				} else { state = new JSONObject(); previousBytes = "{}".getBytes(StandardCharsets.UTF_8); }
				if (!authorization.getAsBoolean()) throw new IOException("Authorization expired");
				String previousPrimary = state.optString("primary"), previousAdditional = state.optString("additional");
				int previousInterval = state.optInt(ReauthenticationPolicy.KEY, 0);
				String previousMode = state.optString(AuthenticationPolicy.KEY), previousBiometricAlias = state.optString("biometric_alias");
				boolean previousBiometric = state.optBoolean("biometric");
				T result = change.apply(state);
				byte[] expected = SecurityStateCodec.encode(state); // BEFORE startWrite: failed size checks leave the old file intact.
				if (!Arrays.equals(previousBytes, expected)) {
					FileOutputStream output = null;
					try {
						output = file.startWrite();
						output.write(expected);
						output.getFD().sync();
						if (!authorization.getAsBoolean()) throw new IOException("Authorization expired");
						file.finishWrite(output);
						output = null;
						if (!Arrays.equals(expected, SecurityStateCodec.read(file, SecurityStateCodec.MAX_BYTES))) throw new IOException("Security state commit failed");
						if (!previousPrimary.equals(state.optString("primary")) || !previousAdditional.equals(state.optString("additional"))
								|| previousInterval != state.optInt(ReauthenticationPolicy.KEY, 0)
								|| !previousMode.equals(state.optString(AuthenticationPolicy.KEY))
								|| !previousBiometricAlias.equals(state.optString("biometric_alias"))
								|| previousBiometric != state.optBoolean("biometric")
								|| state.optBoolean("reset_pending")) SecuritySessionProvider.revoke(context);
						if (!previousPrimary.equals(state.optString("primary"))
								|| !previousMode.equals(state.optString(AuthenticationPolicy.KEY))) AppSecurity.notifyChanged(context);
					} catch (IOException e) {
						if (output != null) file.failWrite(output);
						throw e;
					}
				}
				return result;
			} catch (IOException e) { SecuritySessionProvider.revoke(context); throw e; }
			catch (Exception e) { SecuritySessionProvider.revoke(context); throw new IOException("Security operation failed", e); }
		} finally { MONITOR.unlock(); }
	}

	private static FileLock acquireFileLock(RandomAccessFile mutex) throws IOException {
		long end = SystemClock.elapsedRealtime() + 250;
		while (true) {
			FileLock lock = mutex.getChannel().tryLock();
			if (lock != null) return lock;
			if (SystemClock.elapsedRealtime() >= end) throw new IOException("Security storage busy");
			try { Thread.sleep(10); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Security operation interrupted"); }
		}
	}

	public static boolean enabled(Context context) throws IOException {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		return update(context, s -> authenticationMode(s) != null);
	}
	public static AuthenticationPolicy.Mode authenticationMode(JSONObject state) {
		return AuthenticationPolicy.read(state.has(AuthenticationPolicy.KEY) ? state.optString(AuthenticationPolicy.KEY) : null,
				state.has("primary"), state.optBoolean("biometric"));
	}
	public static AuthenticationPolicy.Mode authenticationMode(Context context) throws IOException {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return null;
		return update(context, SecurityStore::authenticationMode);
	}

	public static boolean pending(Context context) throws IOException {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		return update(context, s -> s.optBoolean("reset_pending"));
	}

	public static boolean screenCaptureBlocked(Context context) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		try {
			return update(context, s -> ScreenCapturePolicy.blocked(authenticationMode(s) != null, s.optBoolean("reset_pending"),
					SecuritySessionProvider.isUnlocked(context), s.optBoolean(ALLOW_SCREENSHOTS, false)));
		} catch (IOException e) { return true; }
	}

	/** Read-only approval for all app-owned windows during the temporary design period. */
	static boolean designCaptureAllowed(Context context) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		try {
			return update(context, s -> ScreenCapturePolicy.designAllowed(
					com.mishiranu.dashchan.BuildConfig.VERSION_NAME, authenticationMode(s) != null,
					s.optBoolean(ALLOW_SCREENSHOTS, false)));
		} catch (IOException e) { return false; }
	}

	static void setScreenshotsAllowed(Context context, boolean allowed,
			java.util.function.BooleanSupplier authorization) throws IOException {
		authorizedUpdate(context, authorization, s -> {
			if (authenticationMode(s) == null || s.optBoolean("reset_pending")) throw new IOException("Capture setting unavailable");
			s.put(ALLOW_SCREENSHOTS, allowed);
			return null;
		});
		AppSecurity.notifyChanged(context);
	}

	public static boolean locked(Context context) {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return false;
		try { return update(context, s -> s.optBoolean("reset_pending")
				|| authenticationMode(s) != null && !SecuritySessionProvider.isUnlocked(context)); }
		catch (IOException e) { return true; }
	}

	static String pinAlias(Context context) throws GeneralSecurityException {
		String actual = directory(context).getAbsolutePath();
		String production = directory(context.getApplicationContext()).getAbsolutePath();
		if (actual.equals(production)) return PIN_KEY;
		// ContextWrapper test sandboxes must never share the real installation's verifier key.
		byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8));
		StringBuilder suffix = new StringBuilder();
		for (byte value : digest) suffix.append(String.format(java.util.Locale.US, "%02x", value & 255));
		return PIN_KEY + ".test." + suffix;
	}
	private static Mac pinMac(Context context) throws GeneralSecurityException, IOException {
		String alias = pinAlias(context);
		KeyStore store = KeyStore.getInstance("AndroidKeyStore");
		store.load(null);
		if (!store.containsAlias(alias)) {
			KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
			generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
					.setDigests(KeyProperties.DIGEST_SHA256).build());
			generator.generateKey();
		}
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init((SecretKey) store.getKey(alias, null));
		return mac;
	}

	private static JSONObject credential(Context context, char[] pin) throws Exception {
		byte[] salt = PinCredential.salt();
		byte[] derived = PinCredential.derive(pin, salt);
		try { return new JSONObject().put("version", 1).put("iterations", PinCredential.ITERATIONS)
				.put("salt", encode(salt)).put("verifier", encode(pinMac(context).doFinal(derived))); }
		finally { Arrays.fill(derived, (byte) 0); }
	}

	private static boolean matches(Context context, JSONObject record, char[] pin) throws Exception {
		if (record == null) return false;
		if (record.optInt("version") != 1 || record.optInt("iterations") != PinCredential.ITERATIONS) throw new IOException("Unsupported credential record");
		byte[] derived = PinCredential.derive(pin, Base64.decode(record.getString("salt"), Base64.NO_WRAP));
		try { return PinCredential.equal(pinMac(context).doFinal(derived), Base64.decode(record.getString("verifier"), Base64.NO_WRAP)); }
		finally { Arrays.fill(derived, (byte) 0); }
	}

	public static Result verify(Context context, char[] pin, boolean primaryOnly) throws IOException {
		JSONObject snapshot = update(context, s -> {
			if (s.optBoolean("reset_pending") && !primaryOnly || delayed(context, s)) return null;
			return new JSONObject(s.toString());
		});
		if (snapshot == null) return Result.WAIT;
		final boolean primary, additional;
		try {
			// Expensive derivation must never hold a lock needed by main-thread lifecycle gates.
			AuthenticationPolicy.Mode mode = authenticationMode(snapshot);
			primary = mode != null && mode.pin && PinCredential.isValid(pin) && matches(context, snapshot.optJSONObject("primary"), pin);
			additional = !primaryOnly && PinCredential.isValid(pin) && matches(context, snapshot.optJSONObject("additional"), pin);
		} catch (Exception e) { throw new IOException("Credential verification unavailable"); }
		return update(context, s -> {
			if (s.optBoolean("reset_pending") && !primaryOnly || delayed(context, s)
					|| !sameCredentials(snapshot, s)) return Result.WAIT;
			if (primary || additional) {
				s.remove("failures"); s.remove("retry_wall"); s.remove("retry_elapsed"); s.remove("retry_boot");
				return additional ? Result.ADDITIONAL : Result.PRIMARY;
			}
			int failures = Math.min(30, s.optInt("failures") + 1);
			s.put("failures", failures);
			if (failures >= 5) {
				long delay = PinRetryPolicy.delayForFailures(failures);
				s.remove("retry_wall");
				s.put("retry_elapsed", SystemClock.elapsedRealtime() + delay).put("retry_boot", bootCount(context));
			}
			return Result.FAILED;
		});
	}

	private static boolean sameCredentials(JSONObject first, JSONObject second) {
		return first.optString("primary").equals(second.optString("primary"))
				&& first.optString("additional").equals(second.optString("additional"))
				&& first.optString(AuthenticationPolicy.KEY).equals(second.optString(AuthenticationPolicy.KEY))
				&& first.optBoolean("biometric") == second.optBoolean("biometric")
				&& first.optString("biometric_alias").equals(second.optString("biometric_alias"));
	}

	private static boolean delayed(Context context, JSONObject s) throws org.json.JSONException {
		if (!s.has("retry_elapsed")) return false;
		int boot = bootCount(context);
		if (boot < 0) throw new IllegalStateException("Boot counter unavailable");
		long now = SystemClock.elapsedRealtime();
		if (s.optInt("retry_boot", -1) != boot) {
			// The elapsed clock restarts at boot. Persist the new anchor once, so every
			// later verification in this boot sees the same bounded deadline.
			s.put("retry_boot", boot).put("retry_elapsed", now + PinRetryPolicy.delayForFailures(s.optInt("failures")));
		}
		s.remove("retry_wall");
		return PinRetryPolicy.remaining(s.optLong("retry_elapsed"), now) > 0;
	}

	private static int bootCount(Context context) {
		return android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
	}

	public static void setPin(Context context, char[] pin, boolean additional) throws IOException {
		setPin(context, pin, additional, () -> true);
	}

	public static void setPin(Context context, char[] pin, boolean additional,
			java.util.function.BooleanSupplier authorization) throws IOException {
		JSONObject snapshot = authorizedUpdate(context, authorization, s -> {
			if (!PinCredential.isValid(pin) || additional && authenticationMode(s) == null || s.optBoolean("reset_pending")) throw new IOException("Invalid PIN");
			pinMac(context); // Serialize only initial Keystore key creation, never PBKDF2.
			return new JSONObject(s.toString());
		});
		final JSONObject candidate;
		try {
			if (matches(context, snapshot.optJSONObject(additional ? "primary" : "additional"), pin)) throw new IOException("PINs must differ");
			candidate = credential(context, pin);
		} catch (Exception e) { throw new IOException("Credential setup unavailable"); }
		authorizedUpdate(context, authorization, s -> {
			if (!sameCredentials(snapshot, s) || s.optBoolean("reset_pending")) throw new IOException("Security settings changed");
			s.put(additional ? "additional" : "primary", candidate);
			return null;
		});
	}
	static JSONObject preparePrimaryCredential(Context context, char[] pin,
			java.util.function.BooleanSupplier authorization) throws IOException {
		JSONObject snapshot = authorizedUpdate(context, authorization, s -> {
			if (!PinCredential.isValid(pin) || s.optBoolean("reset_pending")) throw new IOException("Invalid PIN");
			pinMac(context); return new JSONObject(s.toString());
		});
		try {
			if (matches(context, snapshot.optJSONObject("additional"), pin)) throw new IOException("PINs must differ");
			return credential(context, pin);
		} catch (Exception e) { throw new IOException("Credential setup unavailable"); }
	}
	static void commitAuthenticationMode(Context context, AuthenticationPolicy.Mode mode, JSONObject primary,
			BiometricAttempt biometric, java.util.function.BooleanSupplier authorization) throws IOException {
		authorizedUpdate(context, authorization, s -> {
			if (mode == null || s.optBoolean("reset_pending")) throw new IOException("Authentication mode unavailable");
			if (mode.pin && primary == null && !s.has("primary")) throw new IOException("Main PIN required");
			if (mode.biometric) {
				if (biometric == null) throw new IOException("Biometric proof required");
				s.put("biometric_alias", biometric.retainForCommit());
				BiometricAttempt.activeAlias(context, s); // Reject another sandbox/install's key reference.
			} else s.remove("biometric_alias");
			if (mode.pin) { if (primary != null) s.put("primary", primary); }
			else s.remove("primary");
			s.put("biometric", mode.biometric).put(AuthenticationPolicy.KEY, mode.value);
			return null;
		});
	}

	public static void unlock(Context context) throws IOException {
		unlock(context, SecuritySessionProvider.generation(context));
	}
	static boolean consumeEmptyProfileToken(Context context, String token, long lease) throws IOException {
		boolean consumed = authorizedUpdate(context,
				() -> lease >= 0 && lease == SecuritySessionProvider.generation(context), s -> {
			int boot = bootCount(context);
			if (!s.optBoolean("reset_pending") && token != null && token.equals(s.optString("empty_launch"))
					&& boot >= 0 && boot == s.optInt("empty_launch_boot", -1)
					&& s.optLong("empty_launch_generation", -1) == s.optLong("profile_generation")
					&& SystemClock.elapsedRealtime() <= s.optLong("empty_launch_until")) {
				s.remove("empty_launch"); return true;
			}
			return false;
		});
		if (consumed) unlock(context, lease);
		return consumed;
	}
	public static void unlock(Context context, long generation) throws IOException {
		unlock(context, generation, false, -1);
	}
	/** Only call after accepted PRIMARY/biometric verification, never for an empty-profile receipt. */
	static void authenticatedUnlock(Context context, long generation) throws IOException {
		authenticatedUnlock(context, generation, SystemClock.elapsedRealtime());
	}
	static void authenticatedUnlock(Context context, long generation, long authenticatedAt) throws IOException {
		if (authenticatedAt < 0 || authenticatedAt > SystemClock.elapsedRealtime()) throw new IOException("Invalid authentication time");
		unlock(context, generation, true, authenticatedAt);
	}
	private static void unlock(Context context, long generation, boolean authenticated, long authenticatedAt) throws IOException {
		Integer minutes = authorizedUpdate(context, () -> generation >= 0 && generation == SecuritySessionProvider.generation(context), s -> {
			if (s.optBoolean("reset_pending")) return null;
			s.put("unlocked", true); s.remove("empty_launch");
			return ReauthenticationPolicy.normalize(s.optInt(ReauthenticationPolicy.KEY, 0));
		});
		if (minutes != null) {
			if (authenticated) SecuritySessionProvider.grantAuthenticated(context, generation, minutes, authenticatedAt);
			else SecuritySessionProvider.grant(context, generation);
		}
	}

	/** Settings continuation after fresh PRIMARY, or strong biometrics in BIOMETRIC_ONLY, plus foreground guards. */
	static void restoreSettingsSession(Context context, long verifiedLease) throws IOException {
		authenticatedUnlock(context, verifiedLease);
		if (!SecuritySessionProvider.isUnlocked(context)) throw new IOException("Authorization unavailable");
	}

	public static void lock(Context context) throws IOException {
		SecuritySessionProvider.revoke(context); // Revoke before disk I/O can fail.
	}
	/** Immediately close access, but retain a separately bounded recent-auth grant. */
	static void suspend(Context context) { SecuritySessionProvider.suspend(context); }

	public static void processStarted(Context context, boolean main) throws IOException {
		if (main) SecuritySessionProvider.revoke(context);
		SecuritySessionProvider.foreground(context, false);
	}

	public static void foreground(Context context, boolean active) throws IOException {
		SecuritySessionProvider.foreground(context, active);
		if (active) {
			android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
			if (power == null || !power.isInteractive()) return;
			long lease = SecuritySessionProvider.generation(context);
			boolean allowed = authorizedUpdate(context,
					() -> lease >= 0 && lease == SecuritySessionProvider.generation(context),
					s -> authenticationMode(s) != null && !s.optBoolean("reset_pending")
						&& ReauthenticationPolicy.normalize(s.optInt(ReauthenticationPolicy.KEY, 0)) > 0);
			if (allowed) SecuritySessionProvider.resume(context, lease);
		}
	}

	static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
}
