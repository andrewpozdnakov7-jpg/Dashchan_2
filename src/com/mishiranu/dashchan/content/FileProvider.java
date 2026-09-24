package com.mishiranu.dashchan.content;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.util.MimeTypes;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class FileProvider extends ContentProvider {
	private static final String AUTHORITY = BuildConfig.FILE_PROVIDER_AUTHORITY;
	private static final String PATH_UPDATES = "updates";
	private static final String PATH_DOWNLOADS = "downloads";
	private static final String PATH_SHARE = "share";
	static final String GALLERY_SHARE_FILE_NAME_START = "gallery-share-";

	@Override
	public boolean onCreate() {
		return true;
	}

	public static File getUpdatesDirectory() {
		File directory = MainApplication.getInstance().getExternalCacheDir();
		if (directory == null) {
			return null;
		}
		directory = new File(directory, "updates");
		directory.mkdirs();
		return directory;
	}

	public static File getUpdatesFile(String name) {
		try {
			return name != null && name.indexOf('/') < 0
					? ProviderFileResolver.resolve(getUpdatesDirectory(), name) : null;
		} catch (IOException e) {
			return null;
		}
	}

	public static Uri convertUpdatesUri(Uri uri) {
		if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
			File file = new File(uri.getPath());
			try {
				String path = ProviderFileResolver.relativePath(getUpdatesDirectory(), file);
				if (path.indexOf('/') < 0) {
					return buildUri(PATH_UPDATES, path, null);
				}
			} catch (IOException e) {
				// Leave unsupported locations to the caller, as before.
			}
		}
		return uri;
	}

	private static Uri buildUri(String providerPath, String relativePath, String type) {
		Uri.Builder builder = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(providerPath);
		for (String part : relativePath.split("/")) {
			// Encode each filename component, including spaces, #, % and non-ASCII text.
			builder.appendPath(part);
		}
		if (!StringUtils.isEmpty(type)) {
			builder.appendQueryParameter("type", type);
		}
		return builder.build();
	}

	public static Uri convertDownloadsLegacyFile(File file, String type) {
		try {
			return buildUri(PATH_DOWNLOADS,
					ProviderFileResolver.relativePath(Preferences.getDownloadDirectoryLegacy(), file), type);
		} catch (IOException e) {
			return Uri.fromFile(file);
		}
	}

	public static Uri convertShareFile(File directory, File file, String type) {
		try {
			File root = MainApplication.getInstance().getExternalCacheDir();
			if (root != null && directory != null && root.getCanonicalFile().equals(directory.getCanonicalFile())) {
				String path = ProviderFileResolver.relativePath(root, file);
				if (path.indexOf('/') < 0 && path.startsWith(GALLERY_SHARE_FILE_NAME_START)) {
					return buildUri(PATH_SHARE, path, type);
				}
			}
		} catch (IOException e) {
			// No arbitrary caller-supplied directory is exposed by the provider.
		}
		return Uri.fromFile(file);
	}

	private static List<String> getSegments(Uri uri) {
		if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) {
			throw new IllegalArgumentException("Unknown provider URI");
		}
		List<String> segments = uri.getPathSegments();
		if (segments.size() < 2) {
			throw new IllegalArgumentException("Missing provider path");
		}
		return segments;
	}

	private static File resolveFile(Uri uri) {
		List<String> segments = getSegments(uri);
		String path = String.join("/", segments.subList(1, segments.size()));
		try {
			switch (segments.get(0)) {
				case PATH_UPDATES: {
					return segments.size() == 2 ? getUpdatesFile(path) : null;
				}
				case PATH_DOWNLOADS: {
					return ProviderFileResolver.resolve(Preferences.getDownloadDirectoryLegacy(), path);
				}
				case PATH_SHARE: {
					return segments.size() == 2 && path.indexOf('/') < 0
							&& path.startsWith(GALLERY_SHARE_FILE_NAME_START)
							? ProviderFileResolver.resolve(MainApplication.getInstance().getExternalCacheDir(), path) : null;
				}
				default: throw new IllegalArgumentException("Unknown provider path");
			}
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public String getType(@NonNull Uri uri) {
		File file = resolveFile(uri);
		if (file == null) {
			return null;
		}
		if (PATH_UPDATES.equals(getSegments(uri).get(0))) {
			return "application/vnd.android.package-archive";
		}
		String type = uri.getQueryParameter("type");
		return !StringUtils.isEmpty(type) ? type
				: MimeTypes.forExtension(StringUtils.getFileExtension(file.getName()), "application/octet-stream");
	}

	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new FileNotFoundException("Provider is read-only");
		}
		File file = resolveFile(uri);
		if (file == null) {
			throw new FileNotFoundException("Provider file is unavailable");
		}
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	private static final String[] PROJECTION = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

	@Override
	public Cursor query(@NonNull Uri uri, String[] projection,
			String selection, String[] selectionArgs, String sortOrder) {
		File file = resolveFile(uri);
		if (projection == null) {
			projection = PROJECTION;
		}
		String[] columns = new String[projection.length];
		Object[] values = new Object[projection.length];
		int columnCount = 0;
		for (String column : projection) {
			switch (column) {
				case OpenableColumns.DISPLAY_NAME: {
					columns[columnCount] = OpenableColumns.DISPLAY_NAME;
					values[columnCount++] = file != null ? file.getName() : null;
					break;
				}
				case OpenableColumns.SIZE: {
					columns[columnCount] = OpenableColumns.SIZE;
					values[columnCount++] = file != null ? file.length() : null;
					break;
				}
			}
		}
		MatrixCursor cursor = new MatrixCursor(Arrays.copyOf(columns, columnCount), 1);
		if (file != null) {
			cursor.addRow(Arrays.copyOf(values, columnCount));
		}
		return cursor;
	}

	@Override
	public Uri insert(@NonNull Uri uri, ContentValues values) {
		throw new SQLiteException("Unsupported operation");
	}

	@Override
	public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new SQLiteException("Unsupported operation");
	}

	@Override
	public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
		throw new SQLiteException("Unsupported operation");
	}
}
