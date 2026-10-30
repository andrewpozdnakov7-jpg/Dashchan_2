package com.mishiranu.dashchan.ui;

/** Return-only fade-through policy, evaluated on the same eased fraction as container geometry. */
final class ThreadReturnPresentation {
	private ThreadReturnPresentation() {}

	static float messagesAlpha(float geometryFraction) {
		return 1f - phase(geometryFraction, 0f, .3f);
	}

	static float cardAlpha(float geometryFraction) {
		return phase(geometryFraction, .3f, .6f);
	}

	private static float phase(float value, float start, float end) {
		return Math.max(0f, Math.min(1f, (value - start) / (end - start)));
	}
}
