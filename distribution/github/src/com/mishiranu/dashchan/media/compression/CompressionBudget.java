package com.mishiranu.dashchan.media.compression;

/** Byte targets, without Android, server requests or quality assumptions. */
public final class CompressionBudget {
	private CompressionBudget() {}
	public static long[] allocate(long[] sizes, boolean[] videos, long perFile, long total) {
		if (sizes.length != videos.length || perFile <= 0 || total <= 0) throw new IllegalArgumentException();
		long[] targets = sizes.clone();
		long fixed = 0, adjustable = 0;
		for (int i = 0; i < sizes.length; i++) {
			if (sizes[i] <= 0) throw new IllegalArgumentException();
			if (videos[i]) { targets[i] = Math.min(sizes[i], perFile); adjustable = Math.addExact(adjustable, targets[i]); }
			else { if (sizes[i] > perFile) return null; fixed = Math.addExact(fixed, sizes[i]); }
		}
		if (fixed > total || adjustable > 0 && fixed == total) return null;
		long available = total - fixed;
		if (adjustable > available) {
			for (int i = 0; i < sizes.length; i++) if (videos[i]) {
				targets[i] = java.math.BigInteger.valueOf(targets[i]).multiply(java.math.BigInteger.valueOf(available))
						.divide(java.math.BigInteger.valueOf(adjustable)).longValue();
				if (targets[i] <= 0) return null;
			}
		}
		return targets;
	}
	public static int progress(long completedMs, long currentMs, long totalMs, int currentPercent) {
		if (totalMs <= 0 || currentPercent < 0) return -1;
		return (int) Math.min(99, (completedMs + currentMs * (currentPercent / 100.0)) * 100.0 / totalMs);
	}
	/** Preserve the normal fitted budgets, then give explicitly converted videos spare space.
	 * This permits a codec change to grow a file without forcing quality loss while the total fits. */
	public static long[] allocateForConversion(long[] sizes, boolean[] videos, boolean[] forced, long perFile, long total) {
		if (forced.length != sizes.length) throw new IllegalArgumentException();
		long[] targets = allocate(sizes, videos, perFile, total);
		if (targets == null) return null;
		long used = 0;
		for (long target : targets) used = Math.addExact(used, target);
		long free = total - used;
		for (int round = 0; free > 0 && round <= sizes.length; round++) {
			long weights = 0;
			for (int i = 0; i < sizes.length; i++) if (forced[i] && videos[i] && targets[i] < perFile)
				weights = Math.addExact(weights, targets[i]);
			if (weights == 0) break;
			long added = 0;
			for (int i = 0; i < sizes.length; i++) if (forced[i] && videos[i] && targets[i] < perFile) {
				long share = java.math.BigInteger.valueOf(targets[i]).multiply(java.math.BigInteger.valueOf(free))
						.divide(java.math.BigInteger.valueOf(weights)).longValue();
				share = Math.min(share, perFile - targets[i]); targets[i] += share; added += share;
			}
			if (added == 0) break;
			free -= added;
		}
		return targets;
	}
}
