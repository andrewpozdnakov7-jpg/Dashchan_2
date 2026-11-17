package com.mishiranu.dashchan.content;

import java.util.HashSet;
import java.util.Locale;

/** Process-local ownership across preparation, queued downloads and final publication of one archive. */
public final class LocalArchiveUpdateLease implements AutoCloseable {
	private static final HashSet<String> THREADS = new HashSet<>(), NAMES = new HashSet<>();
	private final String key;
	private String name;
	private boolean closed;
	private LocalArchiveUpdateLease(String key) { this.key = key; }
	public static synchronized LocalArchiveUpdateLease acquire(String key) {
		return THREADS.add(key) ? new LocalArchiveUpdateLease(key) : null;
	}
	public boolean reserveName(String name) {
		synchronized (LocalArchiveUpdateLease.class) {
			if (closed || this.name != null) throw new IllegalStateException();
			String normalized = name.toLowerCase(Locale.ROOT);
			if (!NAMES.add(normalized)) return false;
			this.name = normalized;
			return true;
		}
	}
	@Override public void close() {
		synchronized (LocalArchiveUpdateLease.class) {
			if (closed) return;
			closed = true;
			THREADS.remove(key);
			if (name != null) NAMES.remove(name);
		}
	}
}
