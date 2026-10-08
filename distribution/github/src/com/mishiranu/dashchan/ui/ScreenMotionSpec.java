package com.mishiranu.dashchan.ui;

/** Pure presentation values. Fractions are wall-clock progress; geometry uses a separate easing curve. */
public enum ScreenMotionSpec {
	SECTION(300), FORWARD(360), BACK(320), COMPOSE_OPEN(360), COMPOSE_CLOSE(320);

	public final int durationMs;
	ScreenMotionSpec(int durationMs) { this.durationMs = durationMs; }

	public static ScreenMotionSpec select(boolean section, boolean back, boolean fromCompose, boolean toCompose) {
		if (section) return SECTION;
		if (back && fromCompose) return COMPOSE_CLOSE;
		if (!back && toCompose) return COMPOSE_OPEN;
		return back ? BACK : FORWARD;
	}
	public static float outgoingAlpha(float progress) { return 1f - phase(progress, 0f, .3f); }
	public static float incomingAlpha(float progress) { return phase(progress, .3f, 1f); }
	private static float phase(float value, float start, float end) {
		return Math.max(0f, Math.min(1f, (value - start) / (end - start)));
	}
	public float incomingX(float density, boolean rtl) {
		return (this == FORWARD ? 48f : this == BACK ? -24f : 0f) * density * (rtl ? -1f : 1f);
	}
	public float outgoingX(float density, boolean rtl) {
		return (this == FORWARD ? -24f : this == BACK ? 48f : 0f) * density * (rtl ? -1f : 1f);
	}
	public float incomingY(float density) { return this == COMPOSE_OPEN ? 64f * density : 0f; }
	public float outgoingY(float density) { return this == COMPOSE_CLOSE ? 64f * density : 0f; }
	public float initialScale() { return this == SECTION ? .96f : this == BACK || this == COMPOSE_CLOSE ? 1f : .98f; }
}
