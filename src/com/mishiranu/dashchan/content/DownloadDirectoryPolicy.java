package com.mishiranu.dashchan.content;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Immutable routing settings captured when a user adds a download request. */
public final class DownloadDirectoryPolicy {
	public enum Type {
		IMAGES("images", "Images"), VIDEOS("videos", "Videos"), AUDIO("audio", "Audio"),
		BACKUPS("backups", "Backups"), OTHER("other", "Other");

		public final String preferenceKey;
		public final String defaultName;

		Type(String key, String defaultName) {
			this.preferenceKey = "download_folder_" + key;
			this.defaultName = defaultName;
		}
	}

	private final boolean enabled;
	private final EnumMap<Type, String> names = new EnumMap<>(Type.class);

	public DownloadDirectoryPolicy(boolean enabled, Map<Type, String> names) {
		this.enabled = enabled;
		for (Type type : Type.values()) {
			String name = normalizeName(names.get(type));
			this.names.put(type, name != null ? name : type.defaultName);
		}
	}

	/** A single directory name, never a relative or absolute path. */
	public static String normalizeName(String value) {
		if (value == null) return null;
		String name = value.trim();
		if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".")) return null;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isISOControl(c) || "/\\<>:\"|?*".indexOf(c) >= 0) return null;
		}
		return name;
	}

	public String resolve(Type type, String subdirectory) {
		if (!enabled) return subdirectory;
		return names.get(type) + (subdirectory == null || subdirectory.isEmpty() ? "" : "/" + subdirectory);
	}

	public static Type classify(String fileName, String mimeType) {
		int dot = fileName != null ? fileName.lastIndexOf('.') : -1;
		String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.US) : "";
		// Prefer the final extension; MIME is a local fallback, not a network probe.
		switch (extension) {
			case "jpg": case "jpe": case "jpeg": case "png": case "apng": case "gif": case "webp":
			case "bmp": case "svg": case "svgz": case "ico": case "avif": case "heic": case "heif":
			case "tif": case "tiff": case "jxl": return Type.IMAGES;
			case "mp4": case "webm": case "mkv": case "mov": case "m4v": case "avi": case "3gp":
			case "3g2": case "mpeg": case "mpg": case "ts": case "m2ts": case "ogv": case "wmv":
			case "flv": return Type.VIDEOS;
			case "mp3": case "ogg": case "oga": case "opus": case "flac": case "wav": case "aac":
			case "m4a": case "mid": case "midi": case "amr": case "aiff": case "aif": case "wma":
			case "alac": return Type.AUDIO;
		}
		if (mimeType != null) {
			String mime = mimeType.toLowerCase(Locale.US);
			if (mime.startsWith("image/")) return Type.IMAGES;
			if (mime.startsWith("video/")) return Type.VIDEOS;
			if (mime.startsWith("audio/")) return Type.AUDIO;
		}
		// Only an explicit backup save operation may select BACKUPS, never a downloaded ZIP.
		return Type.OTHER;
	}
}
