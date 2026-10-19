package com.mishiranu.dashchan.content.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded warning identities, not deletion permissions. Never logs or publishes URIs. */
public final class ExternalCleanupLedger {
	private static final String LEGACY_TARGETS = "external_cleanup_targets";
	private static final String HASHES = "external_cleanup_target_hashes";
	private static final String UNKNOWN = "external_cleanup_unknown";
	private static final String INCOMPLETE = "external_cleanup_incomplete";
	private static final int MAX_TARGETS = 32;
	private ExternalCleanupLedger() {}
	private static boolean validIdentity(String value) {
		if (value.length() != 64) return false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
		}
		return true;
	}

	static String identity(String tree) {
		if (tree == null || tree.isEmpty()) return null;
		try {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(tree.getBytes(StandardCharsets.UTF_8));
			char[] hex = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
			for (int i = 0; i < bytes.length; i++) {
				result[i * 2] = hex[(bytes[i] & 255) >>> 4]; result[i * 2 + 1] = hex[bytes[i] & 15];
			}
			return new String(result);
		} catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
	}

	/** Lossless for up to 32 known targets. Overflow/invalid legacy entries retain an unknown warning. */
	static void normalize(JSONObject state) throws JSONException {
		if (!state.has(LEGACY_TARGETS) && !state.has(HASHES) && !state.has(INCOMPLETE) && !state.has(UNKNOWN)) return;
		LinkedHashSet<String> known = new LinkedHashSet<>();
		boolean unknown = state.optBoolean(UNKNOWN);
		JSONArray hashes = state.optJSONArray(HASHES), legacy = state.optJSONArray(LEGACY_TARGETS);
		if (state.has(HASHES) && hashes == null || state.has(LEGACY_TARGETS) && legacy == null) unknown = true;
		if (hashes != null) for (int i = 0; i < hashes.length(); i++) {
			Object value = hashes.opt(i);
			if (!(value instanceof String) || !validIdentity((String) value)) { unknown = true; continue; }
			if (!known.contains(value) && known.size() >= MAX_TARGETS) unknown = true;
			else known.add((String) value);
		}
		if (legacy != null) for (int i = 0; i < legacy.length(); i++) {
			Object value = legacy.opt(i);
			String hash = value instanceof String ? identity((String) value) : null;
			if (hash == null || !known.contains(hash) && known.size() >= MAX_TARGETS) unknown = true;
			else known.add(hash);
		}
		if (state.optBoolean(INCOMPLETE) && known.isEmpty()) unknown = true;
		state.remove(LEGACY_TARGETS);
		state.put(HASHES, new JSONArray(known));
		if (unknown) state.put(UNKNOWN, true); else state.remove(UNKNOWN);
		if (unknown || !known.isEmpty()) state.put(INCOMPLETE, true); else state.remove(INCOMPLETE);
	}

	static void skipped(JSONObject state, String tree) throws JSONException {
		normalize(state);
		JSONArray targets = state.optJSONArray(HASHES);
		if (targets == null) targets = new JSONArray();
		String hash = identity(tree);
		boolean present = false;
		for (int i = 0; i < targets.length(); i++) if (targets.optString(i).equals(hash)) present = true;
		if (hash == null || !present && targets.length() >= MAX_TARGETS) state.put(UNKNOWN, true);
		else if (!present) targets.put(hash);
		state.put(HASHES, targets); state.put(INCOMPLETE, true);
	}

	static void cleared(JSONObject state, String tree) throws JSONException {
		normalize(state);
		JSONArray old = state.optJSONArray(HASHES), remaining = new JSONArray();
		String hash = identity(tree);
		if (old != null) for (int i = 0; i < old.length(); i++) {
			String target = old.getString(i);
			if (!target.equals(hash)) remaining.put(target);
		}
		state.put(HASHES, remaining);
		if (state.optBoolean(UNKNOWN) || remaining.length() > 0) state.put(INCOMPLETE, true);
		else state.remove(INCOMPLETE);
	}

	public static boolean hasIncomplete(JSONObject state) {
		JSONArray hashes = state.optJSONArray(HASHES), legacy = state.optJSONArray(LEGACY_TARGETS);
		return state.optBoolean(INCOMPLETE) || state.optBoolean(UNKNOWN)
				|| hashes != null && hashes.length() > 0 || legacy != null && legacy.length() > 0;
	}
}
