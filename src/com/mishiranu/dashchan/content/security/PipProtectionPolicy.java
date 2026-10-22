package com.mishiranu.dashchan.content.security;

/** PiP is visible even while paused; actual loss of authorization always wins. */
final class PipProtectionPolicy {
	private PipProtectionPolicy() {}
	static boolean cover(boolean protectedProfile, boolean locked, boolean visiblePip,
			boolean hidePip, boolean backgroundCover) {
		return protectedProfile && (locked || (visiblePip ? hidePip : backgroundCover));
	}
}
