package com.mishiranu.dashchan.content.security;

/** A bounded authentication interval, measured with a monotonic clock, never calendar time. */
public final class ReauthenticationPolicy {
	public static final String KEY = "reauthentication_minutes";
	private static final int[] MINUTES = {0, 3, 5, 10, 30, 60, 120, 300};
	private ReauthenticationPolicy() {}
	public static int[] choices() { return MINUTES.clone(); }
	public static int normalize(int minutes) {
		for (int choice : MINUTES) if (choice == minutes) return minutes;
		return 0;
	}
	public static int indexOf(int minutes) {
		for (int i = 0; i < MINUTES.length; i++) if (MINUTES[i] == minutes) return i;
		return 0;
	}
	static final class Grant {
		private final long authenticatedAt, duration;
		Grant(long authenticatedAt, int minutes) {
			this.authenticatedAt = authenticatedAt;
			duration = normalize(minutes) * 60_000L;
		}
		boolean valid(long now) {
			return duration > 0 && authenticatedAt >= 0 && now >= authenticatedAt && now - authenticatedAt < duration;
		}
	}

	static boolean canRestore(long authenticatedAt, int minutes, int savedBoot, int currentBoot, long now) {
		return savedBoot >= 0 && currentBoot == savedBoot && new Grant(authenticatedAt, minutes).valid(now);
	}
}
