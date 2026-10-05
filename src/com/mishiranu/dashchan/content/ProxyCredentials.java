package com.mishiranu.dashchan.content;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Encrypts proxy credentials before they enter the ordinary, backed-up preferences file. */
public final class ProxyCredentials {
	private static final String KEY_ALIAS = "io.dashchan2.proxy_credentials";
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final String PREFIX = "v1:";

	private ProxyCredentials() {}

	private static KeyStore openKeyStore() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
		keyStore.load(null);
		return keyStore;
	}

	private static SecretKey getKey(boolean create) throws Exception {
		SecretKey key = (SecretKey) openKeyStore().getKey(KEY_ALIAS, null);
		if (key == null && create) {
			KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
			generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
					KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
			key = generator.generateKey();
		}
		if (key == null) throw new IllegalStateException("Proxy credential key is unavailable");
		return key;
	}

	public static synchronized String encrypt(String chanName, String username, String password) {
		try {
			JSONObject object = new JSONObject();
			object.put("username", username);
			object.put("password", password);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
			cipher.updateAAD(chanName.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(object.toString().getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
					+ Base64.encodeToString(encrypted, Base64.NO_WRAP);
		} catch (Exception e) {
			// Never fall back to storing the plaintext password.
			throw new IllegalStateException("Could not protect proxy credentials", e);
		}
	}

	public static synchronized String[] decrypt(String chanName, String value) throws Exception {
		if (value == null || !value.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Unsupported proxy credential format");
		}
		int separator = value.indexOf(':', PREFIX.length());
		if (separator < 0) throw new IllegalArgumentException("Invalid proxy credential format");
		byte[] iv = Base64.decode(value.substring(PREFIX.length(), separator), Base64.NO_WRAP);
		byte[] encrypted = Base64.decode(value.substring(separator + 1), Base64.NO_WRAP);
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
		cipher.updateAAD(chanName.getBytes(StandardCharsets.UTF_8));
		JSONObject object = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
		String username = object.getString("username");
		String password = object.getString("password");
		if (username.isEmpty() || password.isEmpty()) throw new JSONException("Incomplete credentials");
		return new String[] {username, password};
	}
}
