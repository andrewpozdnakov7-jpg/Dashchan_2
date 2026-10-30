package com.mishiranu.dashchan.widget;

/** A temporary, invertible scrollbar scale when a background append extends the list. */
final class FastScrollProjection {
	static final long DURATION_MS = 200;
	private static final float EPSILON = .000001f;
	private float anchor, shownAnchor;
	private long started;
	private boolean active, frozen;

	void reset() { active = frozen = false; }
	void rebase(float canonical, float shown, long now, boolean frozen) {
		reset();
		if (!(canonical > 0f && canonical < 1f) || !Float.isFinite(shown)) return;
		anchor = canonical;
		shownAnchor = Math.max(EPSILON, Math.min(1f - EPSILON, shown));
		started = now; this.frozen = frozen;
		active = Math.abs(shownAnchor - anchor) > EPSILON;
	}
	private float shownAnchor(long now) {
		if (!active || frozen) return shownAnchor;
		float fraction = Math.max(0f, Math.min(1f, (now - started) / (float) DURATION_MS));
		// Zero endpoint velocities; no Animator or callbacks are needed after the thumb disappears.
		float eased = fraction * fraction * (3f - 2f * fraction);
		return shownAnchor + (anchor - shownAnchor) * eased;
	}
	boolean isRunning(long now) { return active && !frozen && now - started < DURATION_MS; }
	void setFrozen(boolean value, long now) {
		if (!active || frozen == value) return;
		if (value) {
			if (!isRunning(now)) { reset(); return; }
			shownAnchor = shownAnchor(now);
		}
		started = now; frozen = value;
	}
	float project(float value, long now) {
		float x = clamp(value);
		if (!active || x == 0f || x == 1f || !frozen && now - started >= DURATION_MS) return x;
		float shown = shownAnchor(now);
		return x <= anchor ? x * shown / anchor
				: shown + (x - anchor) * (1f - shown) / (1f - anchor);
	}
	float unproject(float value, long now) {
		float x = clamp(value);
		if (!active || x == 0f || x == 1f || !frozen && now - started >= DURATION_MS) return x;
		float shown = shownAnchor(now);
		return x <= shown ? x * anchor / shown
				: anchor + (x - shown) * (1f - anchor) / (1f - shown);
	}
	private static float clamp(float value) { return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value)); }
}
