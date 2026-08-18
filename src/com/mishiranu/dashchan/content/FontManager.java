package com.mishiranu.dashchan.content;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class FontManager {
	public static final String FONT_SYSTEM = "system";
	private static final String CUSTOM_PREFIX = "custom:";
	private static final String CATALOG_PREFIX = "catalog:";
	private static final String CATALOG_TITLE_PREFIX = "font_catalog_title_";
	public static final int MAX_FONT_SIZE = 20 * 1024 * 1024;

	public static final class FontOption {
		public final String id;
		public final String name;

		private FontOption(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private static final List<FontOption> KNOWN_CATALOG_FONTS = Collections.unmodifiableList(Arrays.asList(
			new FontOption(CATALOG_PREFIX + "roboto", "Roboto"),
			new FontOption(CATALOG_PREFIX + "inter", "Inter"),
			new FontOption(CATALOG_PREFIX + "noto_sans", "Noto Sans"),
			new FontOption(CATALOG_PREFIX + "noto_serif", "Noto Serif"),
			new FontOption(CATALOG_PREFIX + "open_sans", "Open Sans"),
			new FontOption(CATALOG_PREFIX + "pt_sans", "PT Sans"),
			new FontOption(CATALOG_PREFIX + "pt_serif", "PT Serif"),
			new FontOption(CATALOG_PREFIX + "ubuntu", "Ubuntu"),
			new FontOption(CATALOG_PREFIX + "montserrat", "Montserrat"),
			new FontOption(CATALOG_PREFIX + "open_dyslexic", "OpenDyslexic")));

	private static Application application;
	private static String cachedId;
	private static Typeface cachedTypeface;

	private FontManager() {}

	public static void register(Application application) {
		FontManager.application = application;
		String selected = Preferences.getApplicationFont();
		// Preserve custom:* values and migrate only IDs that previously referred to bundled assets.
		for (FontOption option : KNOWN_CATALOG_FONTS) {
			if (getCatalogId(option.id).equals(selected)) {
				Preferences.setApplicationFont(option.id);
				break;
			}
		}
		getSelectedTypeface(application);
	}

	public static void invalidate() {
		synchronized (FontManager.class) {
			cachedId = null;
			cachedTypeface = null;
		}
		Application application = FontManager.application;
		if (application != null) {
			getSelectedTypeface(application);
		}
	}

	public static void apply(View root) {
		applyTree(root.getContext(), root);
	}

	/**
	 * Applies the selected application family while preserving the weight and italic style
	 * established by the view's theme or text appearance.
	 */
	public static void applyTypeface(TextView view) {
		Typeface selectedTypeface = getSelectedTypeface(view.getContext());
		if (selectedTypeface != null) {
			applyTextView(view, selectedTypeface);
		}
	}

	public static List<FontOption> getKnownCatalogFonts() {
		return KNOWN_CATALOG_FONTS;
	}

	public static String getCatalogPreferenceId(String catalogId) {
		if (!isValidCatalogId(catalogId)) {
			throw new IllegalArgumentException("Invalid catalog font ID");
		}
		return CATALOG_PREFIX + catalogId;
	}

	public static void selectCatalogFont(Context context, String catalogId) {
		if (!isCatalogFontInstalled(context, catalogId)) {
			throw new IllegalStateException("Catalog font is not installed");
		}
		Preferences.setApplicationFont(getCatalogPreferenceId(catalogId));
		invalidate();
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
			result.add(new FontOption(CUSTOM_PREFIX + fileName, name));
		}
		return result;
	}

	public static List<FontOption> getDownloadedCatalogFonts(Context context) {
		File[] files = getCatalogFontDirectory(context).listFiles(FontManager::isFontFile);
		if (files == null || files.length == 0) {
			return Collections.emptyList();
		}
		Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
		ArrayList<FontOption> result = new ArrayList<>(files.length);
		for (File file : files) {
			String fileName = file.getName();
			int dotIndex = fileName.lastIndexOf('.');
			String catalogId = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
			if (!isValidCatalogId(catalogId)) {
				continue;
			}
			String title = Preferences.PREFERENCES.getString(CATALOG_TITLE_PREFIX + catalogId,
					getKnownCatalogTitle(catalogId));
			result.add(new FontOption(CATALOG_PREFIX + catalogId, title));
		}
		return result;
	}

	public static boolean isCatalogFontInstalled(Context context, String catalogId) {
		return findCatalogFontFile(context, catalogId) != null;
	}

	public static String getSelectedMissingCatalogId(Context context) {
		String selected = Preferences.getApplicationFont();
		if (!selected.startsWith(CATALOG_PREFIX)) {
			return null;
		}
		String catalogId = getCatalogId(selected);
		return isValidCatalogId(catalogId) && !isCatalogFontInstalled(context, catalogId) ? catalogId : null;
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
						if (total > MAX_FONT_SIZE) {
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

	public static void installCatalogFont(Context context, InputStream input, long expectedSize,
			String expectedSha256, String catalogId, String title, String requiredSelection) throws IOException {
		if (!isValidCatalogId(catalogId) || title == null || title.trim().isEmpty()
				|| expectedSize <= 0L || expectedSize > MAX_FONT_SIZE
				|| expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) {
			throw new IOException("Invalid catalog font metadata");
		}
		File directory = getCatalogFontDirectory(context);
		File temporary = File.createTempFile("download-", ".tmp", directory);
		try {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IOException(e);
			}
			long total = 0L;
			try (BufferedInputStream bufferedInput = new BufferedInputStream(input);
					BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
				byte[] buffer = new byte[8192];
				for (int count; (count = bufferedInput.read(buffer)) != -1;) {
					if (count == 0) {
						continue;
					}
					total += count;
					if (total > expectedSize || total > MAX_FONT_SIZE) {
						throw new IOException("Font is too large");
					}
					digest.update(buffer, 0, count);
					output.write(buffer, 0, count);
				}
			}
			if (total != expectedSize || !toHex(digest.digest()).equals(expectedSha256)) {
				throw new IOException("Font integrity check failed");
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
			File destination = new File(directory, catalogId + extension);
			File alternative = new File(directory, catalogId + (".ttf".equals(extension) ? ".otf" : ".ttf"));
			File backup = new File(directory, catalogId + ".backup");
			if (backup.exists() && !backup.delete()) {
				throw new IOException("Cannot prepare font update");
			}
			if (destination.exists() && !destination.renameTo(backup)) {
				throw new IOException("Cannot prepare font update");
			}
			if (!temporary.renameTo(destination)) {
				if (backup.exists()) {
					backup.renameTo(destination);
				}
				throw new IOException("Cannot store font");
			}
			backup.delete();
			if (alternative.exists()) {
				alternative.delete();
			}
			Preferences.PREFERENCES.edit().put(CATALOG_TITLE_PREFIX + catalogId, title.trim()).close();
			String preferenceId = getCatalogPreferenceId(catalogId);
			if (requiredSelection == null || requiredSelection.equals(Preferences.getApplicationFont())) {
				Preferences.setApplicationFont(preferenceId);
				invalidate();
			}
		} finally {
			if (temporary.exists()) {
				temporary.delete();
			}
		}
	}

	public static boolean deleteCustomFont(Context context, String id) {
		boolean deleted;
		if (id.startsWith(CUSTOM_PREFIX)) {
			String fileName = id.substring(CUSTOM_PREFIX.length());
			if (!fileName.equals(new File(fileName).getName())) {
				return false;
			}
			deleted = new File(getCustomFontDirectory(context), fileName).delete();
		} else if (id.startsWith(CATALOG_PREFIX)) {
			String catalogId = getCatalogId(id);
			File file = findCatalogFontFile(context, catalogId);
			deleted = file != null && file.delete();
			if (deleted) {
				Preferences.PREFERENCES.edit().remove(CATALOG_TITLE_PREFIX + catalogId).close();
			}
		} else {
			return false;
		}
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
				} else if (id.startsWith(CATALOG_PREFIX)) {
					File file = findCatalogFontFile(context, getCatalogId(id));
					if (file != null) {
						typeface = new Typeface.Builder(file).setFallback("sans-serif").build();
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
		Typeface current = view.getTypeface();
		if (current != null && current.equals(Typeface.create("monospace", current.getStyle()))) {
			return;
		}
		int weight = current != null ? current.getWeight() : 400;
		boolean italic = current != null && current.isItalic();
		Typeface typeface = Typeface.create(selectedTypeface, weight, italic);
		if (!typeface.equals(current)) {
			view.setTypeface(typeface);
		}
	}

	private static File getCustomFontDirectory(Context context) {
		return context.getDir("fonts", Context.MODE_PRIVATE);
	}

	private static File getCatalogFontDirectory(Context context) {
		File directory = new File(getCustomFontDirectory(context), "catalog");
		directory.mkdirs();
		return directory;
	}

	private static File findCatalogFontFile(Context context, String catalogId) {
		if (!isValidCatalogId(catalogId)) {
			return null;
		}
		File directory = getCatalogFontDirectory(context);
		File ttf = new File(directory, catalogId + ".ttf");
		if (ttf.isFile()) {
			return ttf;
		}
		File otf = new File(directory, catalogId + ".otf");
		return otf.isFile() ? otf : null;
	}

	private static boolean isValidCatalogId(String catalogId) {
		return catalogId != null && catalogId.matches("[a-z0-9][a-z0-9_]{0,63}");
	}

	private static String getCatalogId(String preferenceId) {
		return preferenceId != null && preferenceId.startsWith(CATALOG_PREFIX)
				? preferenceId.substring(CATALOG_PREFIX.length()) : preferenceId;
	}

	private static String getKnownCatalogTitle(String catalogId) {
		for (FontOption option : KNOWN_CATALOG_FONTS) {
			if (catalogId.equals(getCatalogId(option.id))) {
				return option.name;
			}
		}
		return catalogId;
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
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

}
