package com.mishiranu.dashchan.content;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

public final class FontManager {
	public static final String FONT_SYSTEM = "system";
	private static final String CUSTOM_PREFIX = "custom:";
	private static final int MAX_CUSTOM_FONT_SIZE = 20 * 1024 * 1024;

	public static final class FontOption {
		public final String id;
		public final String name;
		private final String assetPath;

		private FontOption(String id, String name, String assetPath) {
			this.id = id;
			this.name = name;
			this.assetPath = assetPath;
		}
	}

	private static final List<FontOption> BUILT_IN_FONTS = Collections.unmodifiableList(Arrays.asList(
			new FontOption("roboto", "Roboto", "fonts/roboto.ttf"),
			new FontOption("inter", "Inter", "fonts/inter.ttf"),
			new FontOption("noto_sans", "Noto Sans", "fonts/noto_sans.ttf"),
			new FontOption("noto_serif", "Noto Serif", "fonts/noto_serif.ttf"),
			new FontOption("open_sans", "Open Sans", "fonts/open_sans.ttf"),
			new FontOption("pt_sans", "PT Sans", "fonts/pt_sans.ttf"),
			new FontOption("pt_serif", "PT Serif", "fonts/pt_serif.ttf"),
			new FontOption("ubuntu", "Ubuntu", "fonts/ubuntu.ttf"),
			new FontOption("montserrat", "Montserrat", "fonts/montserrat.ttf"),
			new FontOption("open_dyslexic", "OpenDyslexic", "fonts/open_dyslexic.otf")));

	private static final WeakHashMap<Activity, ActivityController> CONTROLLERS = new WeakHashMap<>();
	private static final WeakHashMap<TextView, AppliedTypeface> APPLIED_VIEWS = new WeakHashMap<>();
	private static Application application;
	private static String cachedId;
	private static Typeface cachedTypeface;
	private static int generation;

	private FontManager() {}

	public static void register(Application application) {
		FontManager.application = application;
		getSelectedTypeface(application);
		application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			@Override
			public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
				attach(activity);
			}

			@Override public void onActivityStarted(@NonNull Activity activity) {}
			@Override public void onActivityResumed(@NonNull Activity activity) {}
			@Override public void onActivityPaused(@NonNull Activity activity) {}
			@Override public void onActivityStopped(@NonNull Activity activity) {}
			@Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

			@Override
			public void onActivityDestroyed(@NonNull Activity activity) {
				synchronized (CONTROLLERS) {
					CONTROLLERS.remove(activity);
				}
			}
		});
	}

	public static void attach(Activity activity) {
		if (FONT_SYSTEM.equals(Preferences.getApplicationFont())) {
			ResourceUtils.setApplicationTypeface(null);
			return;
		}
		synchronized (CONTROLLERS) {
			if (!CONTROLLERS.containsKey(activity)) {
				CONTROLLERS.put(activity, new ActivityController(activity));
			}
		}
	}

	public static void invalidate() {
		synchronized (FontManager.class) {
			cachedId = null;
			cachedTypeface = null;
			generation++;
		}
		Application application = FontManager.application;
		if (application != null) {
			getSelectedTypeface(application);
		}
	}

	public static void apply(View root) {
		applyTree(root.getContext(), root);
	}

	public static List<FontOption> getBuiltInFonts() {
		return BUILT_IN_FONTS;
	}

	public static List<FontOption> getCustomFonts(Context context) {
		File[] files = getCustomFontDirectory(context).listFiles(FontManager::isFontFile);
		if (files == null || files.length == 0) {
			return Collections.emptyList();
		}
		Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
		ArrayList<FontOption> result = new ArrayList<>(files.length);
		for (File file : files) {
			String fileName = file.getName();
			int dotIndex = fileName.lastIndexOf('.');
			String name = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
			result.add(new FontOption(CUSTOM_PREFIX + fileName, name, null));
		}
		return result;
	}

	public static String importCustomFont(Context context, Uri uri) throws IOException {
		String displayName = queryDisplayName(context, uri);
		File directory = getCustomFontDirectory(context);
		File temporary = File.createTempFile("import-", ".tmp", directory);
		try {
			try (InputStream input = context.getContentResolver().openInputStream(uri)) {
				if (input == null) {
					throw new IOException("Empty font stream");
				}
				try (BufferedInputStream bufferedInput = new BufferedInputStream(input);
						BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
					byte[] buffer = new byte[8192];
					int total = 0;
					for (int count; (count = bufferedInput.read(buffer)) != -1;) {
						if (count == 0) {
							continue;
						}
						total += count;
						if (total > MAX_CUSTOM_FONT_SIZE) {
							throw new IOException("Font is too large");
						}
						output.write(buffer, 0, count);
					}
				}
			}
			String extension = detectFontExtension(temporary);
			if (extension == null) {
				throw new IOException("Unsupported font format");
			}
			try {
				Typeface typeface = new Typeface.Builder(temporary).setFallback("sans-serif").build();
				if (typeface == null) {
					throw new IOException("Invalid font");
				}
			} catch (RuntimeException e) {
				throw new IOException("Invalid font", e);
			}
			String baseName = sanitizeFileName(displayName);
			int dotIndex = baseName.lastIndexOf('.');
			if (dotIndex > 0) {
				baseName = baseName.substring(0, dotIndex);
			}
			if (baseName.isEmpty()) {
				baseName = "custom_font";
			}
			File destination = uniqueFile(directory, baseName, extension);
			if (!temporary.renameTo(destination)) {
				throw new IOException("Cannot store font");
			}
			String id = CUSTOM_PREFIX + destination.getName();
			Preferences.setApplicationFont(id);
			invalidate();
			return id;
		} finally {
			if (temporary.exists()) {
				temporary.delete();
			}
		}
	}

	public static boolean deleteCustomFont(Context context, String id) {
		if (!id.startsWith(CUSTOM_PREFIX)) {
			return false;
		}
		String fileName = id.substring(CUSTOM_PREFIX.length());
		if (!fileName.equals(new File(fileName).getName())) {
			return false;
		}
		boolean deleted = new File(getCustomFontDirectory(context), fileName).delete();
		if (deleted) {
			if (id.equals(Preferences.getApplicationFont())) {
				Preferences.setApplicationFont(FONT_SYSTEM);
			}
			invalidate();
		}
		return deleted;
	}

	private static Typeface getSelectedTypeface(Context context) {
		String id = Preferences.getApplicationFont();
		if (FONT_SYSTEM.equals(id)) {
			synchronized (FontManager.class) {
				cachedId = id;
				cachedTypeface = null;
				ResourceUtils.setApplicationTypeface(null);
			}
			return null;
		}
		synchronized (FontManager.class) {
			if (id.equals(cachedId)) {
				return cachedTypeface;
			}
			Typeface typeface = null;
			try {
				if (id.startsWith(CUSTOM_PREFIX)) {
					String fileName = id.substring(CUSTOM_PREFIX.length());
					if (fileName.equals(new File(fileName).getName())) {
						File file = new File(getCustomFontDirectory(context), fileName);
						if (file.isFile()) {
							typeface = new Typeface.Builder(file).setFallback("sans-serif").build();
						}
					}
				} else {
					for (FontOption option : BUILT_IN_FONTS) {
						if (option.id.equals(id)) {
							typeface = new Typeface.Builder(context.getAssets(), option.assetPath)
									.setFallback("sans-serif").build();
							break;
						}
					}
				}
			} catch (RuntimeException e) {
				e.printStackTrace();
			}
			cachedId = id;
			cachedTypeface = typeface;
			ResourceUtils.setApplicationTypeface(typeface);
			return typeface;
		}
	}

	private static void applyTree(Context context, View root) {
		Typeface selectedTypeface = getSelectedTypeface(context);
		if (selectedTypeface == null) {
			return;
		}
		ArrayDeque<View> views = new ArrayDeque<>();
		views.add(root);
		while (!views.isEmpty()) {
			View view = views.removeFirst();
			if (view instanceof TextView) {
				applyTextView((TextView) view, selectedTypeface);
			}
			if (view instanceof ViewGroup) {
				ViewGroup group = (ViewGroup) view;
				for (int i = 0; i < group.getChildCount(); i++) {
					views.addLast(group.getChildAt(i));
				}
			}
		}
	}

	private static void applyTextView(TextView view, Typeface selectedTypeface) {
		synchronized (APPLIED_VIEWS) {
			Typeface current = view.getTypeface();
			AppliedTypeface applied = APPLIED_VIEWS.get(view);
			if (applied != null && applied.generation == generation && applied.typeface.equals(current)) {
				return;
			}
			if (current != null && current.equals(Typeface.create("monospace", current.getStyle()))) {
				APPLIED_VIEWS.put(view, new AppliedTypeface(generation, current));
				return;
			}
			int weight = current != null ? current.getWeight() : 400;
			boolean italic = current != null && current.isItalic();
			Typeface typeface = Typeface.create(selectedTypeface, weight, italic);
			view.setTypeface(typeface);
			APPLIED_VIEWS.put(view, new AppliedTypeface(generation, typeface));
		}
	}

	private static final class AppliedTypeface {
		private final int generation;
		private final Typeface typeface;

		private AppliedTypeface(int generation, Typeface typeface) {
			this.generation = generation;
			this.typeface = typeface;
		}
	}

	private static File getCustomFontDirectory(Context context) {
		return context.getDir("fonts", Context.MODE_PRIVATE);
	}

	private static boolean isFontFile(File directory, String name) {
		String lower = name.toLowerCase(Locale.US);
		return lower.endsWith(".ttf") || lower.endsWith(".otf");
	}

	private static String queryDisplayName(Context context, Uri uri) {
		try (Cursor cursor = context.getContentResolver().query(uri,
				new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				String name = cursor.getString(0);
				if (name != null && !name.isEmpty()) {
					return name;
				}
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
		}
		return "custom_font";
	}

	private static String sanitizeFileName(String name) {
		return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
	}

	private static String detectFontExtension(File file) throws IOException {
		byte[] magic = new byte[4];
		try (InputStream input = new FileInputStream(file)) {
			if (input.read(magic) != magic.length) {
				return null;
			}
		}
		if (magic[0] == 0 && magic[1] == 1 && magic[2] == 0 && magic[3] == 0) {
			return ".ttf";
		}
		if (magic[0] == 'O' && magic[1] == 'T' && magic[2] == 'T' && magic[3] == 'O') {
			return ".otf";
		}
		return null;
	}

	private static File uniqueFile(File directory, String baseName, String extension) {
		File file = new File(directory, baseName + extension);
		for (int index = 1; file.exists(); index++) {
			file = new File(directory, baseName + "_" + index + extension);
		}
		return file;
	}

	private static final class ActivityController {
		private final Activity activity;

		private ActivityController(Activity activity) {
			this.activity = activity;
			attachWindow(activity.getWindow());
			if (activity instanceof FragmentActivity) {
				((FragmentActivity) activity).getSupportFragmentManager().registerFragmentLifecycleCallbacks(
						new FragmentManager.FragmentLifecycleCallbacks() {
					@Override
					public void onFragmentViewCreated(@NonNull FragmentManager fragmentManager,
							@NonNull Fragment fragment, @NonNull View view, Bundle savedInstanceState) {
						applyTree(activity, view);
					}

					@Override
					public void onFragmentStarted(@NonNull FragmentManager fragmentManager,
							@NonNull Fragment fragment) {
						if (fragment instanceof DialogFragment) {
							Dialog dialog = ((DialogFragment) fragment).getDialog();
							if (dialog != null) {
								attachWindow(dialog.getWindow());
							}
						}
					}
				}, true);
			}
		}

		private void attachWindow(Window window) {
			if (window == null) {
				return;
			}
			View decorView = window.getDecorView();
			ViewTreeObserver observer = decorView.getViewTreeObserver();
			observer.addOnGlobalLayoutListener(() -> applyTree(activity, decorView));
			applyTree(activity, decorView);
		}
	}
}
