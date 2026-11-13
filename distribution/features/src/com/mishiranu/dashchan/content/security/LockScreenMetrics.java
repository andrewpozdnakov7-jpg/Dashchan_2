package com.mishiranu.dashchan.content.security;

/** Pixels in, pixels out: width is the safe window width before form padding. */
final class LockScreenMetrics {
	private LockScreenMetrics() {}
	static int biometricCat(int safeWindowWidth, float density, boolean compact) {
		int normal = Math.round(Math.min(Math.max(0, safeWindowWidth) * .47f, 200 * density));
		return compact ? Math.min(normal, Math.round(96 * density)) : normal;
	}
}
