package com.mishiranu.dashchan.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Bounded, data-minimizing report format. Never serializes Throwable messages or arbitrary UI text. */
public final class CrashReportPolicy {
	public static final int MAX_REPORTS = 8;
	public static final int MAX_BYTES = 64 * 1024;
	public static final long MAX_AGE = 14L * 24 * 60 * 60 * 1000;
	public static final long SHARE_LIFETIME = 10L * 60 * 1000;
	private static final java.util.regex.Pattern ANR_FRAME = java.util.regex.Pattern.compile(
			"\\s*at ([A-Za-z0-9_.$<>]+)\\(([A-Za-z0-9_.$-]+)(?::(-?[0-9]+))?\\)\\s*");
	private CrashReportPolicy() {}
	public static boolean reportId(String id) {
		return id != null && id.matches("report-[0-9]{1,20}-[a-f0-9]{12}\\.txt");
	}
	public static boolean crashReason(int reason) {
		// ApplicationExitInfo constants, present since API 30: CRASH, CRASH_NATIVE, ANR.
		return reason == 4 || reason == 5 || reason == 6;
	}
	public static boolean shareName(String name) {
		return name != null && name.matches("share-[a-f0-9]{32}\\.txt");
	}
	public static boolean freshShare(long created, long now) {
		return created > 0 && now >= created && now - created <= SHARE_LIFETIME;
	}
	public static String token(String value, int limit) {
		if (value == null) return "unknown";
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < value.length() && result.length() < limit; i++) {
			char c = value.charAt(i);
			result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
					|| "._$<>:-".indexOf(c) >= 0 ? c : '_');
		}
		return result.toString();
	}
	public static String summary(String session, int code, String version, String event) {
		return summary(session, code, version, event, 0, "");
	}
	public static String summary(String session, int code, String version, String event, int sdk, String variant) {
		if (session == null || !session.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid session");
		if (code < 0) throw new IllegalArgumentException("Invalid version");
		return "SLC1|" + session + "|" + code + "|" + token(version, 20) + "|" + token(event, 32)
				+ "|" + Math.max(0, Math.min(999, sdk)) + "|" + token(variant, 16);
	}
	public static String[] parseSummary(String value) {
		if (value == null || value.length() > 128) return null;
		String[] parts = value.split("\\|", -1);
		return parts.length == 7 && parts[0].equals("SLC1") && parts[1].matches("[a-f0-9]{32}")
				&& parts[2].matches("[0-9]{1,10}") && parts[3].matches("[A-Za-z0-9._$<>:-]{0,20}")
				&& parts[4].matches("[A-Za-z0-9._$<>:-]{0,32}") && parts[5].matches("[0-9]{1,3}")
				&& parts[6].matches("[A-Za-z0-9._$<>:-]{0,16}") ? parts : null;
	}
	public static String stack(Throwable error) {
		StringBuilder text = new StringBuilder();
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int cause = 0; error != null && cause < 8 && visited.add(error); cause++, error = error.getCause()) {
			text.append(cause == 0 ? "Exception: " : "Caused by: ")
					.append(token(error.getClass().getName(), 200)).append('\n');
			StackTraceElement[] frames = error.getStackTrace();
			for (int i = 0; i < frames.length && i < 64 && text.length() < 28000; i++) {
				StackTraceElement frame = frames[i];
				text.append("  at ").append(token(frame.getClassName(), 160)).append('.')
						.append(token(frame.getMethodName(), 100)).append('(')
						.append(sourceFile(frame.getFileName())).append(':').append(frame.getLineNumber()).append(")\n");
			}
			if (text.length() >= 28000) break;
		}
		text.append("Exception messages and suppressed details are intentionally omitted.\n");
		return text.toString();
	}
	public static String anrFrame(String line) {
		if (line == null || line.length() > 500) return null;
		java.util.regex.Matcher match = ANR_FRAME.matcher(line);
		return match.matches() ? "  at " + match.group(1) + "(" + sourceFile(match.group(2))
				+ (match.group(3) != null ? ":" + match.group(3) : "") + ")" : null;
	}
	private static String sourceFile(String value) {
		return value != null && (value.equals("SourceFile") || value.matches("[A-Za-z0-9_.$-]{1,90}\\.(java|kt)")) ? value : "source";
	}
}
