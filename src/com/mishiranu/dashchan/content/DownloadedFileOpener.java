package com.mishiranu.dashchan.content;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.Pair;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.MimeTypes;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DownloadedFileOpener {
	private DownloadedFileOpener() {}

	public static void open(Context context, DataFile file, boolean allowWrite, Runnable completion) {
		if (!file.exists()) {
			completion.run();
			return;
		}
		String extension = StringUtils.getFileExtension(file.getName());
		String type = MimeTypes.forExtension(extension, "image/jpeg");
		Pair<File, Uri> fileOrUri = file.getFileOrUri();
		if (fileOrUri.second != null) {
			openUri(context, fileOrUri.second, type, allowWrite, completion);
		} else if (fileOrUri.first != null) {
			File legacyFile = fileOrUri.first;
			AtomicBoolean handled = new AtomicBoolean();
			MediaScannerConnection.scanFile(context, new String[] {legacyFile.getAbsolutePath()}, null,
					(path, uri) -> {
						if (uri != null && handled.compareAndSet(false, true)) {
							openUri(context, uri, type, allowWrite, completion);
						}
					});
			ConcurrentUtils.HANDLER.postDelayed(() -> {
				if (handled.compareAndSet(false, true)) {
					openUri(context, FileProvider.convertDownloadsLegacyFile(legacyFile, type),
							type, allowWrite, completion);
				}
			}, 1000);
		} else {
			completion.run();
		}
	}

	private static void openUri(Context context, Uri uri, String type, boolean allowWrite, Runnable completion) {
		ConcurrentUtils.HANDLER.post(() -> openUriOnMainThread(context, uri, type, allowWrite, completion));
	}

	private static void openUriOnMainThread(Context context, Uri uri, String type,
			boolean allowWrite, Runnable completion) {
		try {
			if (uri == null) {
				throw new ActivityNotFoundException();
			}
			context.startActivity(new Intent(Intent.ACTION_VIEW)
					.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION |
							(allowWrite ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0))
					.setDataAndType(uri, type));
		} catch (ActivityNotFoundException e) {
			ClickableToast.show(R.string.unknown_address);
		} finally {
			completion.run();
		}
	}
}
