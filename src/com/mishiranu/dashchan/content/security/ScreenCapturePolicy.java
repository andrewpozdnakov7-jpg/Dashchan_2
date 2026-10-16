package com.mishiranu.dashchan.content.security;

/** Capture permission never authenticates or cancels recovery. */
public final class ScreenCapturePolicy {
	private ScreenCapturePolicy() {}
	public static boolean blocked(boolean enabled, boolean pending, boolean unlocked, boolean allowed) {
		return pending || enabled && (!unlocked || !allowed);
	}

	/** Temporary all-window design mode. The exact allowlist expires at 3.2.70. */
	public static boolean designRelease(String version) {
		return "3.2.62".equals(version) || "3.2.63".equals(version) || "3.2.64".equals(version)
				|| "3.2.65".equals(version) || "3.2.66".equals(version) || "3.2.67".equals(version)
				|| "3.2.68".equals(version) || "3.2.69".equals(version);
	}
	public static boolean designAllowed(String version, boolean enabled, boolean allowed) {
		// Capture permission does not authenticate or change a pending cleanup operation.
		return designRelease(version) && enabled && allowed;
	}
}
