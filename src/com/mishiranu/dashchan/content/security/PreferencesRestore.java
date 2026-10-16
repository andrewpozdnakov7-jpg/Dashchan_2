package com.mishiranu.dashchan.content.security;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Applying a backup is an explicit settings change, never a temporary loss of SAF access. */
public final class PreferencesRestore {
	private PreferencesRestore() {}

	public static void revokeFolderConsent(Context context) throws IOException {
		if (!com.mishiranu.dashchan.BuildConfig.ENABLE_APP_SECURITY_SETTINGS) return;
		SecurityStore.update(context, state -> {
			if (state.optBoolean("reset_pending")) throw new IOException("Profile cleanup pending");
			// A backup never supplies consent for destructive deletion, even for the same folder.
			state.remove("delete_downloads_uri"); state.remove("delete_downloads_label");
			return null;
		});
	}

	public static synchronized boolean applyPending(Context context, File pending, File destination) throws IOException {
		if (!pending.exists() && !new File(pending.getPath() + ".bak").exists()) return false;
		// Repeat on startup: a backup may have been queued by an older application version,
		// or consent may have changed since it was staged. Failure leaves the old settings intact.
		revokeFolderConsent(context);
		AtomicFile source = new AtomicFile(pending), target = new AtomicFile(destination);
		FileOutputStream output = null;
		final java.security.MessageDigest expected, actual;
		try {
			expected = java.security.MessageDigest.getInstance("SHA-256");
			actual = java.security.MessageDigest.getInstance("SHA-256");
		} catch (java.security.GeneralSecurityException e) { throw new IOException("Digest unavailable", e); }
		try (FileInputStream input = source.openRead()) {
			output = target.startWrite();
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) {
				output.write(buffer, 0, count); expected.update(buffer, 0, count);
			}
			output.getFD().sync();
			target.finishWrite(output); output = null;
		} catch (IOException e) {
			if (output != null) target.failWrite(output);
			throw e;
		} catch (RuntimeException e) {
			if (output != null) target.failWrite(output);
			throw new IOException("Preferences restore unavailable", e);
		}
		// AtomicFile has a void finishWrite; never discard the queued backup based only
		// on that return. Verify the committed bytes before removing the pending file.
		try (FileInputStream input = target.openRead()) {
			byte[] buffer = new byte[8192];
			for (int count; (count = input.read(buffer)) != -1;) actual.update(buffer, 0, count);
		}
		if (!java.security.MessageDigest.isEqual(expected.digest(), actual.digest())) {
			throw new IOException("Preferences restore commit failed");
		}
		source.delete();
		return true;
	}
}
