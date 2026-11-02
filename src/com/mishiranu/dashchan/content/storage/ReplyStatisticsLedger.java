package com.mishiranu.dashchan.content.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;

/** Counts discovered reply posts, not references or notifications. Owned by StatisticsStorage's monitor. */
final class ReplyStatisticsLedger {
	final HashMap<String, Long> counts = new HashMap<>();
	final HashSet<String> seen = new HashSet<>();
	long since;

	ReplyStatisticsLedger(long since) { this.since = since; }
	ReplyStatisticsLedger copy() {
		ReplyStatisticsLedger copy = new ReplyStatisticsLedger(since);
		copy.counts.putAll(counts); copy.seen.addAll(seen);
		return copy;
	}
	boolean record(String chan, String board, String thread, String post, boolean count) {
		if (!seen.add(fingerprint(chan, board != null ? board : "", thread, post))) return false;
		if (count) counts.put(chan, counts.getOrDefault(chan, 0L) + 1);
		return true;
	}
	void reset(long now) {
		counts.clear(); since = now;
		// Keep deduplication after reset: reloading old replies must not repopulate the counter.
	}
	private static String fingerprint(String... parts) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : parts) {
				byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
				for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
				digest.update(bytes);
			}
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest.digest()) {
				result.append(Character.forDigit((value >>> 4) & 15, 16));
				result.append(Character.forDigit(value & 15, 16));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}
}
