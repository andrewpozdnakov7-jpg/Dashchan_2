package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Staged keys never replace the active key before successful authentication and state commit. */
final class BiometricAttempt implements AutoCloseable {
	private static final String LEGACY_ALIAS = "slooop.app-lock.biometric.v1";
	final String alias;
	final Mac mac;
	private final boolean created;
	private final byte[] challenge = PinCredential.salt();
	private boolean verified, retain;
	private BiometricAttempt(String alias, Mac mac, boolean created) { this.alias = alias; this.mac = mac; this.created = created; }
	private static String prefix(Context context) throws Exception {
		String actual = SecurityStore.directory(context).getAbsolutePath();
		String production = SecurityStore.directory(context.getApplicationContext()).getAbsolutePath();
		if (actual.equals(production)) return LEGACY_ALIAS + ".staged.";
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8));
		StringBuilder suffix = new StringBuilder();
		for (byte b : hash) suffix.append(String.format(java.util.Locale.US, "%02x", b & 255));
		return LEGACY_ALIAS + ".test." + suffix + ".staged.";
	}
	static String activeAlias(Context context, org.json.JSONObject state) throws Exception {
		String alias = state.optString("biometric_alias", LEGACY_ALIAS);
		boolean production = SecurityStore.directory(context).equals(SecurityStore.directory(context.getApplicationContext()));
		String prefix = prefix(context);
		if (!(production && LEGACY_ALIAS.equals(alias)) && !(alias.startsWith(prefix)
				&& alias.substring(prefix.length()).matches("[a-f0-9]{32}"))) throw new IOException("Invalid biometric key reference");
		return alias;
	}
	static BiometricAttempt current(Context context) throws Exception {
		String alias = SecurityStore.update(context, s -> {
			AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(s);
			if (mode == null || !mode.biometric) throw new IOException("Biometrics not configured");
			return activeAlias(context, s);
		});
		return open(alias, false);
	}
	static BiometricAttempt create(Context context) throws Exception {
		String alias = prefix(context) + java.util.UUID.randomUUID().toString().replace("-", "");
		KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
		generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
				.setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true)
				.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
				.setInvalidatedByBiometricEnrollment(true).build());
		generator.generateKey();
		try { return open(alias, true); }
		catch (Exception e) { delete(alias); throw e; }
	}
	private static BiometricAttempt open(String alias, boolean created) throws Exception {
		KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
		if (!store.containsAlias(alias)) throw new IOException("Biometric key unavailable");
		Mac mac = Mac.getInstance("HmacSHA256"); mac.init((SecretKey) store.getKey(alias, null));
		return new BiometricAttempt(alias, mac, created);
	}
	void verify(BiometricPrompt.CryptoObject crypto) throws Exception {
		if (verified || crypto == null || crypto.getMac() != mac) throw new IOException("Biometric operation mismatch");
		byte[] proof = mac.doFinal(challenge);
		Arrays.fill(proof, (byte) 0); verified = true;
	}
	String retainForCommit() throws IOException {
		if (!verified) throw new IOException("Biometric proof required");
		// A failed commit/readback may already have persisted the new alias. Never delete
		// that key on an uncertain outcome; an unused alias is safer than locking out its owner.
		retain = true; return alias;
	}
	boolean matchesCurrent(Context context, org.json.JSONObject state) throws Exception {
		return verified && alias.equals(activeAlias(context, state));
	}
	private static void delete(String alias) {
		try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); }
		catch (Exception ignored) { /* An unreferenced Keystore key cannot grant application access. */ }
	}
	@Override public void close() {
		Arrays.fill(challenge, (byte) 0);
		if (created && !retain) delete(alias);
	}
}
