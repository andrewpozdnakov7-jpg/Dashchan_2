package com.mishiranu.dashchan.content;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import chan.content.Chan;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.MainActivity;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LauncherIconManager {
	public static final String VALUE_DASHCHAN_2 = "dashchan_2";
	public static final String VALUE_SLOPCHAN = "slopchan";
	public static final String VALUE_DVACH = "dvach";
	public static final String VALUE_SLOOP = "sloop";
	public static final String VALUE_SLOPCHAN_PLAIN = "slopchan_plain";
	public static final String VALUE_SLOPCHAN_1 = "slopchan_1";
	public static final String VALUE_SLOPCHAN_2 = "slopchan_2";
	public static final String LOGO_DEFAULT = "default";
	public static final String LOGO_OLD_SCHOOL = "old_school";
	public static final String LOGO_MATERIAL_YOU = "material_you";
	public static final String LOGO_2 = "logo_2";
	public static final String LOGO_3 = "logo_3";
	public static final String LOGO_4 = "logo_4";
	public static final String LOGO_5 = "logo_5";
	public static final String LOGO_6 = "logo_6";
	public static final String LOGO_7 = "logo_7";
	public static final String LOGO_8 = "logo_8";
	public static final String LOGO_9 = "logo_9";
	public static final String LOGO_10 = "logo_10";
	public static final String LOGO_11 = "logo_11";
	public static final String LOGO_DVACH_PASS = "dvach_pass";
	public static final String LOGO_SUBSCRIBER = "subscriber";

	private static final boolean PRESET_LOGOS_READY = true;

	public enum LogoAccess {PUBLIC, DVACH_PASS, SUBSCRIBER}

	public static final class LogoOption {
		public final String value;
		@StringRes public final int titleResId;
		@DrawableRes public final int iconResId;
		public final LogoAccess access;
		private final String classSuffix;

		private LogoOption(String value, @StringRes int titleResId, @DrawableRes int iconResId,
				LogoAccess access, String classSuffix) {
			this.value = value;
			this.titleResId = titleResId;
			this.iconResId = iconResId;
			this.access = access;
			this.classSuffix = classSuffix;
		}
	}

	private static final List<LogoOption> LOGO_OPTIONS = Collections.unmodifiableList(Arrays.asList(
			new LogoOption(LOGO_DEFAULT, R.string.application_logo_default, R.drawable.application_logo_default,
					LogoAccess.PUBLIC, ""),
			new LogoOption(LOGO_OLD_SCHOOL, R.string.application_logo_old_school, R.mipmap.ic_launcher,
					LogoAccess.PUBLIC, "OldSchool"),
			new LogoOption(LOGO_MATERIAL_YOU, R.string.application_logo_material_you,
					R.mipmap.ic_launcher_material, LogoAccess.PUBLIC, "MaterialYou"),
			new LogoOption(LOGO_2, R.string.application_logo_2, R.drawable.application_logo_2,
					LogoAccess.PUBLIC, "Logo2"),
			new LogoOption(LOGO_7, R.string.application_logo_7, R.drawable.application_logo_7,
					LogoAccess.PUBLIC, "Logo7"),
			new LogoOption(LOGO_3, R.string.application_logo_3, R.drawable.application_logo_3,
					LogoAccess.PUBLIC, "Logo3"),
			new LogoOption(LOGO_4, R.string.application_logo_4, R.drawable.application_logo_4,
					LogoAccess.PUBLIC, "Logo4"),
			new LogoOption(LOGO_5, R.string.application_logo_5, R.drawable.application_logo_5,
					LogoAccess.PUBLIC, "Logo5"),
			new LogoOption(LOGO_6, R.string.application_logo_6, R.drawable.application_logo_6,
					LogoAccess.PUBLIC, "Logo6"),
			new LogoOption(LOGO_8, R.string.application_logo_8, R.drawable.application_logo_8,
					LogoAccess.PUBLIC, "Logo8"),
			new LogoOption(LOGO_9, R.string.application_logo_9, R.drawable.application_logo_9,
					LogoAccess.PUBLIC, "Logo9"),
			new LogoOption(LOGO_10, R.string.application_logo_10, R.drawable.application_logo_10,
					LogoAccess.PUBLIC, "Logo10"),
			new LogoOption(LOGO_11, R.string.application_logo_11, R.drawable.application_logo_11,
					LogoAccess.PUBLIC, "Logo11"),
			new LogoOption(LOGO_DVACH_PASS, R.string.application_logo_dvach_pass,
					R.drawable.application_logo_pass, LogoAccess.DVACH_PASS, "Pass"),
			new LogoOption(LOGO_SUBSCRIBER, R.string.application_logo_subscriber,
					R.drawable.application_logo_subscriber, LogoAccess.SUBSCRIBER, "Subscriber")));

	private static final String CLASS_DASHCHAN_2 = "com.mishiranu.dashchan.launcher.Dashchan2Alias";
	private static final String CLASS_SLOPCHAN = "com.mishiranu.dashchan.launcher.SlopchanAlias";
	private static final String CLASS_DVACH = "com.mishiranu.dashchan.launcher.DvachAlias";
	private static final List<String> APPLICATION_NAMES = Collections.unmodifiableList(Arrays.asList(VALUE_SLOOP,
			VALUE_DASHCHAN_2, VALUE_SLOPCHAN, VALUE_DVACH, VALUE_SLOPCHAN_PLAIN, VALUE_SLOPCHAN_1,
			VALUE_SLOPCHAN_2));
	private LauncherIconManager() {}

	public static boolean isValidValue(String value) {
		return APPLICATION_NAMES.contains(value);
	}

	public static List<String> getApplicationNames() {
		return APPLICATION_NAMES;
	}

	public static boolean isValidLogoValue(String value) {
		for (LogoOption option : LOGO_OPTIONS) {
			if (option.value.equals(value)) {
				return true;
			}
		}
		return false;
	}

	public static boolean arePresetLogosReady() {
		return PRESET_LOGOS_READY;
	}

	public static List<LogoOption> getLogoOptions() {
		return LOGO_OPTIONS;
	}

	public static LogoOption getLogoOption(String value) {
		for (LogoOption option : LOGO_OPTIONS) {
			if (option.value.equals(value)) {
				return option;
			}
		}
		return LOGO_OPTIONS.get(0);
	}

	public static boolean hasDvachPass() {
		try {
			Chan chan = Chan.get(VALUE_DVACH);
			return VALUE_DVACH.equals(chan.name)
					&& Preferences.checkHasMultipleValues(Preferences.getCaptchaPass(chan));
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static void apply(Context context, String value) {
		apply(context, value, Preferences.getApplicationLogo());
	}

	public static void apply(Context context, String value, String logoValue) {
		String applicationName = isValidValue(value) ? value : VALUE_DVACH;
		LogoOption selectedLogo = getLogoOption(logoValue);
		String selectedClass = getClassName(applicationName, selectedLogo);
		PackageManager packageManager = context.getPackageManager();
		setEnabled(packageManager, context, selectedClass, true);
		for (LogoOption option : LOGO_OPTIONS) {
			for (String name : APPLICATION_NAMES) {
				String className = getClassName(name, option);
				setEnabled(packageManager, context, className, className.equals(selectedClass));
			}
		}
	}

	private static String getClassName(String value, LogoOption option) {
		String prefix;
		switch (value) {
			case VALUE_SLOOP: prefix = "Sloop"; break;
			case VALUE_DASHCHAN_2: prefix = "Dashchan2"; break;
			case VALUE_SLOPCHAN: prefix = "Slopchan"; break;
			case VALUE_SLOPCHAN_PLAIN: prefix = "SlopchanPlain"; break;
			case VALUE_SLOPCHAN_1: prefix = "Slopchan1"; break;
			case VALUE_SLOPCHAN_2: prefix = "Slopchan2"; break;
			default: prefix = "Dvach"; break;
		}
		return "com.mishiranu.dashchan.launcher." + prefix + option.classSuffix + "Alias";
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
				Icon.createWithResource(context, R.drawable.application_logo_default));
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
