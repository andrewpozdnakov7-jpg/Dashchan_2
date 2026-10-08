package com.mishiranu.dashchan.ui.gallery;

/** UI-thread state: retain the latest target without cancelling a busy decoder on every move. */
final class VideoScrubSession {
	static final long PREVIEW_INTERVAL_MS = 100L;
	private boolean active;
	private long target = -1L;
	private long sentTarget = -1L;
	private long lastPreviewAt;
	private boolean sentPreview;
	private boolean deferredCompletion;

	void start() {
		active = true;
		target = sentTarget = -1L;
		sentPreview = false;
		deferredCompletion = false;
	}

	void update(long position) {
		if (active) target = Math.max(0L, position);
	}

	long takePreview(long now, boolean decoderBusy) {
		if (!active || decoderBusy || target < 0L || target == sentTarget
				|| sentPreview && now - lastPreviewAt < PREVIEW_INTERVAL_MS) return -1L;
		sentTarget = target;
		lastPreviewAt = now;
		sentPreview = true;
		return target;
	}

	void deferCompletion() {
		if (active) deferredCompletion = true;
	}

	void clearDeferredCompletion() {
		deferredCompletion = false;
	}

	boolean takeDeferredCompletion() {
		boolean result = deferredCompletion;
		deferredCompletion = false;
		return result;
	}

	/** The final target bypasses preview throttling; a tap without movement produces no seek. */
	long finish() {
		long result = active ? target : -1L;
		cancel();
		return result;
	}

	void cancel() {
		active = false;
		target = sentTarget = -1L;
		sentPreview = false;
		deferredCompletion = false;
	}
}
