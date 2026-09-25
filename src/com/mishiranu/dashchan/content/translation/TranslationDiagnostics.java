package com.mishiranu.dashchan.content.translation;

import com.mishiranu.dashchan.util.Logger;

/** Privacy-safe diagnostics for local translation test builds. */
public final class TranslationDiagnostics {
	public static final String TAG = "SlooopTranslation";
	// Diagnostic test builds only. Disable before committing or publishing a release build.
	private static final boolean ENABLED = false;

	private TranslationDiagnostics() {}

	public static void log(String component, String event, Object... details) {
		write(Logger.Type.DEBUG, component, event, details);
	}

	public static void error(String component, String event, Object... details) {
		write(Logger.Type.ERROR, component, event, details);
	}

	public static int length(String value) {
		return value != null ? value.length() : -1;
	}

	public static String safeError(String message) {
		if (message == null) {
			return "none";
		}
		String safe = message.replace('\n', ' ').replace('\r', ' ').trim().toLowerCase(java.util.Locale.US);
		String unquoted = safe.length() >= 2 && safe.charAt(0) == '"' && safe.charAt(safe.length() - 1) == '"'
				? safe.substring(1, safe.length() - 1) : safe;
		if ("applied".equals(unquoted) || "cached".equals(unquoted) || "missing".equals(unquoted)
				|| "failed".equals(unquoted)) {
			return unquoted;
		}
		if (safe.contains("timed out") || safe.contains("timeout")) {
			return "timeout";
		}
		if (safe.contains("not installed") || safe.contains("unavailable")) {
			return "unavailable";
		}
		if (safe.contains("disconnect")) {
			return "disconnected";
		}
		if (safe.contains("language package")) {
			return "model_missing";
		}
		if (safe.contains("cannot connect") || safe.contains("cannot start")) {
			return "bind_failed";
		}
		if (safe.contains("invalid response")) {
			return "invalid_response";
		}
		if (safe.contains("direction changed")) {
			return "direction_changed";
		}
		if (safe.contains("stopped")) {
			return "stopped";
		}
		if (safe.contains("failed")) {
			return "failed";
		}
		return "other_" + safe.length() + "_chars";
	}

	private static void write(Logger.Type type, String component, String event, Object... details) {
		// These components log only lifecycle/state/counts, never text, URLs or cache keys.
		if (!ENABLED && !"cache".equals(component) && !"cold_start".equals(component)
				&& !"display".equals(component)) {
			return;
		}
		int detailsLength = details != null ? details.length : 0;
		Object[] data = new Object[4 + detailsLength];
		data[0] = "component";
		data[1] = component;
		data[2] = "event";
		data[3] = event;
		if (detailsLength > 0) {
			System.arraycopy(details, 0, data, 4, detailsLength);
		}
		Logger.write(type, TAG, data);
	}
}
