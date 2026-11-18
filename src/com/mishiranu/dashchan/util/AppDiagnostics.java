package com.mishiranu.dashchan.util;

import android.content.Context;
import android.util.Log;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** One user-controlled capture; existing independent screen/player buffers keep their budgets. */
public final class AppDiagnostics {
	private static final String FILE_NAME = "app-diagnostics.txt";
	private static boolean saving, pending;
	private static long startedAt;
	private static File screenPart, playerPart;
	private AppDiagnostics() {}
	public static boolean isRecording() { return PostingFormDiagnostics.isRecording() || VideoDiagnostics.isRecording(); }
	public static synchronized boolean isSaving() {
		return saving || PostingFormDiagnostics.isSaving() || VideoDiagnostics.isSaving();
	}
	public static synchronized boolean hasPendingExport() { return pending || PostingFormDiagnostics.hasPendingExport(); }

	public static synchronized boolean start(Context context) {
		if (isRecording() || isSaving() || hasPendingExport()) return false;
		if (!VideoDiagnostics.start()) return false;
		startedAt = System.currentTimeMillis(); screenPart = playerPart = null;
		PostingFormDiagnostics.start(context.getApplicationContext());
		return true;
	}

	/** Freeze both producers at the same request, then join their async exports off the UI thread. */
	public static synchronized void stopAndSave(Context context, Consumer<File> callback) {
		if (isSaving() || !isRecording() && !hasPendingExport()) return;
		saving = true; pending = true;
		boolean screen = PostingFormDiagnostics.isRecording() || PostingFormDiagnostics.hasPendingExport();
		boolean player = VideoDiagnostics.isRecording();
		Join join = new Join(context.getApplicationContext(), callback, (screen ? 1 : 0) + (player ? 1 : 0));
		if (screen) PostingFormDiagnostics.stopAndSave(join.context, file -> {
			synchronized (AppDiagnostics.class) { screenPart = file; }
			join.finished();
		});
		if (player) VideoDiagnostics.stopAsync(file -> {
			synchronized (AppDiagnostics.class) { playerPart = file; }
			join.finished();
		});
		if (!screen && !player) join.export();
	}

	private static final class Join {
		final Context context;
		final Consumer<File> callback;
		int waiting;
		Join(Context context, Consumer<File> callback, int waiting) { this.context = context; this.callback = callback; this.waiting = waiting; }
		void finished() { if (--waiting == 0) export(); }
		void export() {
			if (PostingFormDiagnostics.hasPendingExport()) {
				synchronized (AppDiagnostics.class) { saving = false; }
				if (callback != null) callback.accept(null);
				return; // Keep the successful player part while the screen export is retried.
			}
			final File screen, player;
			final long start = startedAt;
			synchronized (AppDiagnostics.class) { screen = screenPart; player = playerPart; }
			ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
				File file = null;
				try { file = writeReport(context, start, screen, player); }
				catch (IOException | RuntimeException e) { Log.w("AppDiagnostics", "export_failed type=" + e.getClass().getSimpleName()); }
				final File result = file;
				if (result != null) {
					// These are only the two completed exports owned by this capture.
					if (screen != null) screen.delete();
					if (player != null) player.delete();
				}
				ConcurrentUtils.HANDLER.post(() -> {
					synchronized (AppDiagnostics.class) {
						saving = false;
						if (result != null) { pending = false; screenPart = playerPart = null; }
					}
					if (callback != null) callback.accept(result);
				});
			});
		}
	}

	static File writeReport(Context context, long start, File screen, File player) throws IOException {
		File directory = context.getCacheDir();
		File target = new File(directory, FILE_NAME);
		File temporary = File.createTempFile("app-diagnostics-new-", ".tmp", directory);
		try {
			try (FileOutputStream output = new FileOutputStream(temporary)) {
				output.write(("Slooop app diagnostics\nschema=1\nmode=extended\napp=" + BuildConfig.VERSION_NAME
						+ " code=" + BuildConfig.VERSION_CODE + "\nstartedUtcMillis=" + start + "\nstoppedUtcMillis=" + System.currentTimeMillis()
						+ "\nsections_have_independent_timestamps_and_buffers=true\n"
						+ "network_events_in=screens_and_input network_schema=1\n"
						+ "privacy=no_text,no_clipboard,no_passwords,no_urls,no_media,no_accounts,no_device_ids\n").getBytes(StandardCharsets.UTF_8));
				copyPart(output, "screens_and_input", screen);
				copyPart(output, "player_ui_conversion_and_predictive_back", player);
				output.getFD().sync();
			}
			// Same private-cache filesystem. A failed export preserves the previous shared report.
			Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return target;
		} finally { temporary.delete(); }
	}
	private static void copyPart(FileOutputStream output, String name, File file) throws IOException {
		boolean available = file != null && file.isFile();
		output.write(("\n[" + name + "]\navailable=" + available + "\n").getBytes(StandardCharsets.UTF_8));
		if (available) {
			try (FileInputStream input = new FileInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
			}
			output.write('\n');
		}
	}
	public static File getLastFile(Context context) {
		File combined = new File(context.getCacheDir(), FILE_NAME);
		if (combined.isFile()) return combined;
		// Reports saved before the combined UI remain shareable after an upgrade.
		File screen = PostingFormDiagnostics.getLastFile(context), player = VideoDiagnostics.getLastFile();
		return screen == null ? player : player == null || screen.lastModified() >= player.lastModified() ? screen : player;
	}
	public static boolean deleteLastFile(Context context) {
		if (isRecording() || isSaving() || hasPendingExport()) return false;
		File file = getLastFile(context);
		return file == null || file.delete();
	}
}
