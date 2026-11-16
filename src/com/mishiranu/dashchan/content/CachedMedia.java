package com.mishiranu.dashchan.content;

import android.net.Uri;
import com.mishiranu.dashchan.content.model.GalleryItem;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Read-only snapshot of completed cached media. No URL reconstruction, downloads or LRU touches. */
public final class CachedMedia {
	private CachedMedia() {}
	private static final Pattern CACHE_NAME = Pattern.compile("[a-fA-F0-9]{64}");
	private static final Pattern SVG_HEADER = Pattern.compile("<svg(?:\\s|>)");
	private static final String THUMBNAIL_REVISION = "slooop-cache-local-";

	/** Run on a worker. Only small headers are read; no full-size pixels are allocated here. */
	public static ArrayList<GalleryItem> scan(BooleanSupplier cancelled) throws IOException, InterruptedException {
		CacheManager cache = CacheManager.getInstance();
		return scan(cache.getMediaDirectory(), cancelled,
				file -> cache.isCompletedMediaFile(file, file.length(), file.lastModified()));
	}
	static ArrayList<GalleryItem> scan(File directory, BooleanSupplier cancelled) throws IOException, InterruptedException {
		return scan(directory, cancelled, file -> true);
	}
	static ArrayList<GalleryItem> scan(File directory, BooleanSupplier cancelled, Predicate<File> completed)
			throws IOException, InterruptedException {
		if (directory == null) throw new IOException("Media cache unavailable");
		if (!directory.exists()) return new ArrayList<>();
		File[] files = directory.listFiles();
		if (files == null) throw new IOException("Media cache cannot be listed");
		Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
		ArrayList<GalleryItem> result = new ArrayList<>();
		byte[] header = new byte[4096];
		for (File file : files) {
			if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
			// Active downloads have a .part suffix. Do not enter directories or follow symlinks.
			if (!CACHE_NAME.matcher(file.getName()).matches() ||
					!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
			long size = file.length(), modified = file.lastModified();
			if (size <= 0 || !completed.test(file)) continue;
			int length = 0;
			try (FileInputStream input = new FileInputStream(file)) {
				while (length < header.length) {
					int count = input.read(header, length, header.length - length);
					if (count <= 0) break;
					length += count;
				}
			} catch (IOException ignored) { continue; } // Eviction can race this snapshot.
			String extension = extension(header, length);
			if (extension == null || !file.isFile() || file.length() != size || file.lastModified() != modified || !completed.test(file)) continue;
			boolean video = extension.equals("mp4") || extension.equals("mov") || extension.equals("mkv");
			Uri uri = Uri.fromFile(file);
			Uri thumbnail = uri.buildUpon().fragment(THUMBNAIL_REVISION + modified + "-" + size + (video ? "-video" : "-image")).build();
			result.add(GalleryItem.fromRestoreDescriptor(uri.toString(), thumbnail.toString(),
					file.getName().substring(0, 12) + "." + extension, null, null, null, null,
					0, 0, size <= Integer.MAX_VALUE ? (int) size : 0));
		}
		return result;
	}

	public static boolean isVideoThumbnail(Uri uri) {
		String fragment = uri != null ? uri.getFragment() : null;
		return uri != null && "file".equals(uri.getScheme()) && fragment != null &&
				fragment.startsWith(THUMBNAIL_REVISION) && fragment.endsWith("-video");
	}

	static String extension(byte[] data, int length) {
		if (length < 0 || length > data.length) return null;
		if (matches(data, length, 0, 0xff, 0xd8, 0xff)) return "jpg";
		if (matches(data, length, 0, 0x89, 'P', 'N', 'G', 13, 10, 26, 10)) return "png";
		if (matches(data, length, 0, 'G', 'I', 'F', '8') && length >= 6 &&
				(data[4] == '7' || data[4] == '9') && data[5] == 'a') return "gif";
		if (matches(data, length, 0, 'R', 'I', 'F', 'F') && matches(data, length, 8, 'W', 'E', 'B', 'P')) return "webp";
		if (matches(data, length, 0, 'B', 'M')) return "bmp";
		// Both WebM and Matroska are playable by the existing MKV gallery path.
		if (matches(data, length, 0, 0x1a, 0x45, 0xdf, 0xa3)) return "mkv";
		if (length >= 12 && matches(data, length, 4, 'f', 't', 'y', 'p')) {
			String brand = new String(data, 8, 4, StandardCharsets.US_ASCII);
			if (brand.equals("qt  ")) return "mov";
			// Do not mistake HEIF/AVIF images or audio-only MP4 brands for video.
			if (brand.startsWith("iso") || brand.equals("mp41") || brand.equals("mp42") ||
					brand.equals("avc1") || brand.equals("hvc1") || brand.equals("hev1") || brand.startsWith("3gp")) return "mp4";
			return null;
		}
		String xml = new String(data, 0, length, StandardCharsets.UTF_8).trim();
		if ((xml.startsWith("<") || xml.startsWith("\ufeff")) && SVG_HEADER.matcher(xml).find()) return "svg";
		return null;
	}
	private static boolean matches(byte[] data, int length, int offset, int... expected) {
		if (offset + expected.length > length) return false;
		for (int i = 0; i < expected.length; i++) if ((data[offset + i] & 255) != expected[i]) return false;
		return true;
	}
}
