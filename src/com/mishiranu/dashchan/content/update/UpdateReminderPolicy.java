package com.mishiranu.dashchan.content.update;

/** Counts cold main-process launches, not Activity resumes or configuration changes. */
public final class UpdateReminderPolicy {
	private UpdateReminderPolicy() {}
	public static int delay(int postponements) {
		return postponements <= 0 ? 3 : postponements == 1 ? 6 : 15;
	}
	public static long nextLaunch(long launches, int postponements) {
		return launches > Long.MAX_VALUE - delay(postponements) ? Long.MAX_VALUE
				: launches + delay(postponements);
	}
	public static boolean due(long launches, long nextLaunch) { return launches >= nextLaunch; }
	public static long countLaunch(long launches, String previous, String current) {
		return current.equals(previous) || launches == Long.MAX_VALUE ? launches : launches + 1;
	}
}
