package com.mishiranu.dashchan.content.security;

import android.content.Context;
import java.io.IOException;

/** Old Android Bundle/state writers cannot cross a destructive profile reset. */
public final class ProfileGeneration {
	public static final String BUNDLE_KEY = "security.profile.generation";
	private ProfileGeneration() {}
	public static long current(Context context) throws IOException {
		return SecurityStore.update(context, s -> s.optLong("profile_generation"));
	}
	public static boolean canWrite(Context context, long generation) {
		try { return SecurityStore.update(context, s -> !s.optBoolean("reset_pending")
				&& generation == s.optLong("profile_generation")); }
		catch (IOException e) { return false; }
	}
}
