package com.mishiranu.dashchan.content;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.MainActivity;

public final class LauncherIconManager {
	public static final String VALUE_DASHCHAN_2 = "dashchan_2";
	public static final String VALUE_SLOPCHAN = "slopchan";
	public static final String VALUE_DVACH = "dvach";

	private static final String CLASS_DASHCHAN_2 = "com.mishiranu.dashchan.launcher.Dashchan2Alias";
	private static final String CLASS_SLOPCHAN = "com.mishiranu.dashchan.launcher.SlopchanAlias";
	private static final String CLASS_DVACH = "com.mishiranu.dashchan.launcher.DvachAlias";

	private LauncherIconManager() {}

	public static boolean isValidValue(String value) {
		return VALUE_DASHCHAN_2.equals(value) || VALUE_SLOPCHAN.equals(value) || VALUE_DVACH.equals(value);
	}

	public static void apply(Context context, String value) {
		String selectedClass = getClassName(isValidValue(value) ? value : VALUE_DVACH);
		PackageManager packageManager = context.getPackageManager();
		setEnabled(packageManager, context, selectedClass, true);
		setEnabled(packageManager, context, CLASS_DASHCHAN_2, CLASS_DASHCHAN_2.equals(selectedClass));
		setEnabled(packageManager, context, CLASS_SLOPCHAN, CLASS_SLOPCHAN.equals(selectedClass));
		setEnabled(packageManager, context, CLASS_DVACH, CLASS_DVACH.equals(selectedClass));
	}

	private static String getClassName(String value) {
		switch (value) {
			case VALUE_DASHCHAN_2: return CLASS_DASHCHAN_2;
			case VALUE_SLOPCHAN: return CLASS_SLOPCHAN;
			default: return CLASS_DVACH;
		}
	}

	private static void setEnabled(PackageManager packageManager, Context context,
			String className, boolean enabled) {
		ComponentName componentName = new ComponentName(context, className);
		int currentState = packageManager.getComponentEnabledSetting(componentName);
		boolean defaultEnabled = CLASS_DVACH.equals(className);
		if ((currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && defaultEnabled == enabled) ||
				(currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED && enabled) ||
				(currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED && !enabled)) {
			return;
		}
		packageManager.setComponentEnabledSetting(componentName, enabled
				? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
				: PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
	}

	public static boolean requestCustomShortcut(Context context, String name) {
		ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
		if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) {
			return false;
		}
		return requestCustomShortcut(context, shortcutManager, name,
				Icon.createWithResource(context, R.mipmap.ic_launcher));
	}

	public static boolean requestCustomShortcut(Context context, String name, Bitmap bitmap) {
		ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
		if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) {
			return false;
		}
		int maxWidth = shortcutManager.getIconMaxWidth();
		int maxHeight = shortcutManager.getIconMaxHeight();
		Bitmap iconBitmap = bitmap;
		if (bitmap.getWidth() > maxWidth || bitmap.getHeight() > maxHeight) {
			float scale = Math.min((float) maxWidth / bitmap.getWidth(), (float) maxHeight / bitmap.getHeight());
			iconBitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
					Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
		}
		return requestCustomShortcut(context, shortcutManager, name, Icon.createWithAdaptiveBitmap(iconBitmap));
	}

	private static boolean requestCustomShortcut(Context context, ShortcutManager shortcutManager,
			String name, Icon icon) {
		Intent intent = new Intent(context, MainActivity.class)
				.setAction(Intent.ACTION_MAIN)
				.addCategory(Intent.CATEGORY_LAUNCHER)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		String shortcutId = "custom_launcher_" + Integer.toHexString(name.hashCode());
		ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId)
				.setShortLabel(name)
				.setLongLabel(name)
				.setIcon(icon)
				.setIntent(intent)
				.build();
		return shortcutManager.requestPinShortcut(shortcut, null);
	}
}
