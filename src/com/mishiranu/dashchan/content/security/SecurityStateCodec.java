package com.mishiranu.dashchan.content.security;

import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One write/read bound; larger legacy input is accepted only if its warning ledger compacts safely. */
final class SecurityStateCodec {
	static final int MAX_BYTES = 64 * 1024;
	static final int LEGACY_READ_BYTES = 1024 * 1024;
	private static final int TRANSITION_RESERVE = 16 * 1024;
	private SecurityStateCodec() {}

	static byte[] read(AtomicFile file, int limit) throws IOException {
		try (FileInputStream input = file.openRead(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[4096];
			for (int count; (count = input.read(chunk)) != -1;) {
				if (count > limit - buffer.size()) throw new IOException("Security state exceeds read budget");
				buffer.write(chunk, 0, count);
			}
			return buffer.toByteArray();
		}
	}

	static JSONObject decode(byte[] bytes) throws IOException {
		if (bytes.length > LEGACY_READ_BYTES) throw new IOException("Security state exceeds migration budget");
		try {
			JSONObject state = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
			ExternalCleanupLedger.normalize(state);
			encode(state); // Never shrink an oversized non-ledger record by silently dropping fields.
			return state;
		} catch (org.json.JSONException e) { throw new IOException("Invalid security state", e); }
	}

	static byte[] encode(JSONObject state) throws IOException {
		try {
			AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(state);
			if (state.has(AuthenticationPolicy.KEY) && mode != null && mode.biometric
					&& !state.has("biometric_alias")) throw new IllegalArgumentException("Missing biometric key reference");
		}
		catch (IllegalArgumentException e) { throw new IOException("Invalid authentication configuration", e); }
		try { ExternalCleanupLedger.normalize(state); }
		catch (org.json.JSONException e) { throw new IOException("Invalid cleanup ledger", e); }
		String json = state.toString();
		if (json == null) throw new IOException("Invalid security state");
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_BYTES) throw new IOException("Security state exceeds write budget");
		return bytes;
	}

	static void approveDownloadTarget(JSONObject state, String tree, String label) throws Exception {
		if (tree == null || label == null) throw new IOException("Missing cleanup target");
		JSONObject future = new JSONObject(state.toString());
		future.put("delete_downloads_uri", tree).put("delete_downloads_label", label).put("reset_tree", tree);
		// Reserve room for the duplicate URI in the journal, future ledger growth,
		// credential changes, retry counters and the final receipt before accepting consent.
		if (encode(future).length > MAX_BYTES - TRANSITION_RESERVE) throw new IOException("Cleanup target exceeds state budget");
		state.put("delete_downloads_uri", tree).put("delete_downloads_label", label);
	}
}
