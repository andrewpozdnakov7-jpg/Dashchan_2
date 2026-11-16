package com.mishiranu.dashchan.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.util.Pair;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.content.security.PrivacyReset;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/** The same user-confirmed ACTION_SEND/email contract as crash reports; never sends mail itself. */
public final class DiagnosticReportSharing {
	private DiagnosticReportSharing() {}
	static Intent reportIntent(Uri uri) {
		return new Intent(Intent.ACTION_SEND).setType("text/plain")
				.putExtra(Intent.EXTRA_EMAIL, new String[] {"andrewpozdnakov7@gmail.com"})
				.putExtra(Intent.EXTRA_SUBJECT, "Slooop diagnostics")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_STREAM, uri);
	}
	public static void share(Activity activity, File file) {
		final long profile;
		try {
			if (SecurityStore.locked(activity) || PrivacyReset.requiresRecovery(activity)) return;
			profile = ProfileGeneration.current(activity);
		} catch (IOException | RuntimeException e) { ClickableToast.show(R.string.unknown_error); return; }
		WeakReference<Activity> host = new WeakReference<>(activity);
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			Pair<Uri, String> prepared;
			try { prepared = CacheManager.getInstance().prepareFileForShare(file, file.getName()); }
			catch (RuntimeException e) { prepared = null; }
			Pair<Uri, String> result = prepared;
			ConcurrentUtils.HANDLER.post(() -> {
				Activity target = host.get();
				if (target == null || target.isFinishing() || target.isDestroyed()) return;
				if (result == null) { ClickableToast.show(R.string.cache_is_unavailable); return; }
				try {
					if (SecurityStore.locked(target) || PrivacyReset.requiresRecovery(target) || ProfileGeneration.current(target) != profile) return;
					Intent intent = reportIntent(result.first);
					intent.setClipData(ClipData.newUri(target.getContentResolver(), "Slooop diagnostics", result.first));
					target.startActivity(Intent.createChooser(intent, null));
				} catch (IOException | RuntimeException e) { ClickableToast.show(R.string.unknown_error); }
			});
		});
	}
}
