package com.mishiranu.dashchan.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** The non-exported alias is an OS-enforced entry point for app-owned navigation. */
public final class MainNavigationIntent {
	private static final String INTERNAL_COMPONENT = "com.mishiranu.dashchan.ui.InternalNavigation";
	private static final String ACTION_HANDLE_URI = "chan.intent.action.HANDLE_URI";

	private MainNavigationIntent() {}

	public static Intent create(Context context) {
		return new Intent().setComponent(new ComponentName(context, INTERNAL_COMPONENT));
	}

	/** Do not inspect or deserialize extras supplied through a public entry point. */
	public static Intent sanitize(Context context, Intent source) {
		if (source == null) return null;
		ComponentName component = source.getComponent();
		if (component != null && context.getPackageName().equals(component.getPackageName())
				&& INTERNAL_COMPONENT.equals(component.getClassName()) && source.getSelector() == null) {
			return new Intent(source);
		}
		String action = source.getAction();
		Intent result = new Intent(action).setComponent(new ComponentName(context, MainActivity.class));
		// Retain the framework's history marker, but no URI grants, ClipData or app commands.
		result.setFlags(source.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
		if (Intent.ACTION_MAIN.equals(action) && source.getData() == null) {
			if (source.hasCategory(Intent.CATEGORY_LAUNCHER)) result.addCategory(Intent.CATEGORY_LAUNCHER);
			return result;
		}
		if (Intent.ACTION_VIEW.equals(action) || ACTION_HANDLE_URI.equals(action)) {
			Uri uri = source.getData();
			if (uri != null && ("https".equalsIgnoreCase(uri.getScheme())
					|| "http".equalsIgnoreCase(uri.getScheme()))) {
				return result.setData(uri);
			}
		}
		return null;
	}
}
