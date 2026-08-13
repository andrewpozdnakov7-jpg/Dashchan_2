package com.mishiranu.dashchan.content;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class WallpaperManager {
	public static final String CUSTOM_ID = "custom";
	public static final long MAX_FILE_SIZE = 25L * 1024L * 1024L;

	private static final String DIRECTORY = "wallpapers";
	private static final String ACTIVE_FILE = "active";
	private static final String TEMP_FILE = "active.tmp";
	private static final String BACKUP_FILE = "active.bak";
	private static final int MAX_DECODE_SIDE = 2048;

	private WallpaperManager() {}

	private static File getDirectory(Context context) {
		return new File(context.getFilesDir(), DIRECTORY);
	}

	public static File getActiveFile(Context context) {
		return new File(getDirectory(context), ACTIVE_FILE);
	}

	public static boolean hasActiveWallpaper(Context context) {
		return getActiveFile(context).isFile();
	}

	public static void remove(Context context) {
		getActiveFile(context).delete();
		new File(getDirectory(context), TEMP_FILE).delete();
		new File(getDirectory(context), BACKUP_FILE).delete();
		Preferences.PREFERENCES.edit()
				.remove(Preferences.KEY_WALLPAPER_ID)
				.remove(Preferences.KEY_WALLPAPER_TITLE)
				.close();
	}

	public static void importCustom(Context context, Uri uri) throws IOException, SecurityException {
		try (InputStream input = context.getContentResolver().openInputStream(uri)) {
			if (input == null) {
				throw new IOException("Input stream is empty");
			}
			install(context, input, -1L, null, CUSTOM_ID, null);
		}
	}

	public static void install(Context context, InputStream input, long expectedSize,
			@Nullable String expectedSha256, String id, @Nullable String title) throws IOException {
		File directory = getDirectory(context);
		if (!directory.exists() && !directory.mkdirs()) {
			throw new IOException("Unable to create wallpaper directory");
		}
		File temporary = new File(directory, TEMP_FILE);
		CopyResult copyResult;
		try (OutputStream output = new FileOutputStream(temporary)) {
			copyResult = copyLimited(input, output, MAX_FILE_SIZE);
		} catch (IOException e) {
			temporary.delete();
			throw e;
		}
		if (copyResult.length <= 0L || expectedSize > 0L && copyResult.length != expectedSize ||
				expectedSha256 != null && !expectedSha256.equalsIgnoreCase(copyResult.sha256) ||
				!isImage(temporary)) {
			temporary.delete();
			throw new IOException("Invalid wallpaper image");
		}
		File active = getActiveFile(context);
		File backup = new File(directory, BACKUP_FILE);
		backup.delete();
		if (active.exists() && !active.renameTo(backup)) {
			temporary.delete();
			throw new IOException("Unable to replace wallpaper");
		}
		if (!temporary.renameTo(active)) {
			temporary.delete();
			backup.renameTo(active);
			throw new IOException("Unable to store wallpaper");
		}
		backup.delete();
		Preferences.PREFERENCES.edit()
				.put(Preferences.KEY_WALLPAPER_ID, id)
				.put(Preferences.KEY_WALLPAPER_TITLE, title)
				.close();
	}

	private static final class CopyResult {
		public final long length;
		public final String sha256;

		private CopyResult(long length, String sha256) {
			this.length = length;
			this.sha256 = sha256;
		}
	}

	private static CopyResult copyLimited(InputStream input, OutputStream output, long maximum) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		byte[] buffer = new byte[8192];
		long total = 0L;
		int count;
		while ((count = input.read(buffer)) != -1) {
			if (count == 0) {
				continue;
			}
			total += count;
			if (total > maximum) {
				throw new IOException("Wallpaper is too large");
			}
			output.write(buffer, 0, count);
			digest.update(buffer, 0, count);
		}
		StringBuilder builder = new StringBuilder(64);
		for (byte value : digest.digest()) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return new CopyResult(total, builder.toString());
	}

	private static boolean isImage(File file) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file.getAbsolutePath(), options);
		return options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null;
	}

	public static int applyCardOpacity(int color) {
		if (!Preferences.isWallpaperEnabled() || Preferences.getWallpaperId() == null) {
			return color;
		}
		int alpha = Math.round(255f * Preferences.getWallpaperCardOpacity() / 100f);
		return color & 0x00ffffff | alpha << 24;
	}

	public static void applyToPage(FrameLayout layout, ViewGroup content) {
		Context context = layout.getContext();
		if (!Preferences.isWallpaperEnabled() || !hasActiveWallpaper(context)) {
			return;
		}
		content.setBackgroundColor(Color.TRANSPARENT);
		ImageView imageView = new ImageView(context);
		imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
		layout.addView(imageView, 0, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		android.view.View dim = new android.view.View(context);
		dim.setBackgroundColor(Color.argb(Math.round(255f * Preferences.getWallpaperDimAmount() / 100f),
				0, 0, 0));
		layout.addView(dim, 1, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		File file = getActiveFile(context);
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			Bitmap bitmap = decodeForScreen(file);
			ConcurrentUtils.HANDLER.post(() -> {
				if (bitmap != null && imageView.isAttachedToWindow()) {
					imageView.setImageBitmap(bitmap);
				} else {
					if (bitmap != null) {
						bitmap.recycle();
					}
					if (imageView.getParent() == layout) {
						layout.removeView(imageView);
						layout.removeView(dim);
					}
				}
			});
		});
	}

	public static Bitmap decodeForScreen(File file) {
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null;
		}
		int sample = 1;
		while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DECODE_SIDE) {
			sample *= 2;
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = sample;
		options.inPreferredConfig = Bitmap.Config.RGB_565;
		try {
			return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
		} catch (RuntimeException | OutOfMemoryError e) {
			return null;
		}
	}
}
