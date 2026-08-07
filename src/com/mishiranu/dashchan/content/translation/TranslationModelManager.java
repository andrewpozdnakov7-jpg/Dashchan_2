package com.mishiranu.dashchan.content.translation;

import android.os.Handler;
import android.os.Looper;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public final class TranslationModelManager {
	public enum State { NOT_INSTALLED, CHECKING, DOWNLOADING, INSTALLED, ERROR }

	public static final class Snapshot {
		public final State state;
		public final int progress;
		public final String error;

		private Snapshot(State state, int progress, String error) {
			this.state = state;
			this.progress = progress;
			this.error = error;
		}
	}

	public interface Listener {
		void onTranslationModelChanged(TranslationModel.Direction direction, Snapshot snapshot);
	}

	private static final TranslationModelManager INSTANCE = new TranslationModelManager();

	public static TranslationModelManager getInstance() {
		return INSTANCE;
	}

	private final MainApplication application = MainApplication.getInstance();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
	private final EnumMap<TranslationModel.Direction, Snapshot> snapshots =
			new EnumMap<>(TranslationModel.Direction.class);

	private TranslationModelManager() {}

	public void register(Listener listener) {
		listeners.add(listener);
	}

	public void unregister(Listener listener) {
		listeners.remove(listener);
	}

	public synchronized Snapshot getSnapshot(TranslationModel.Direction direction) {
		Snapshot snapshot = snapshots.get(direction);
		if (snapshot != null && (snapshot.state == State.DOWNLOADING || snapshot.state == State.INSTALLED)) {
			return snapshot;
		}
		if (TranslationModel.isInstalled(application, direction)) {
			snapshot = new Snapshot(State.INSTALLED, 100, null);
			snapshots.put(direction, snapshot);
			return snapshot;
		}
		return snapshot != null && snapshot.state == State.ERROR ? snapshot
				: new Snapshot(State.NOT_INSTALLED, 0, null);
	}

	public synchronized void download(TranslationModel.Direction direction) {
		if (getSnapshot(direction).state == State.DOWNLOADING ||
				TranslationModel.isInstalled(application, direction)) {
			return;
		}
		setSnapshotLocked(direction, new Snapshot(State.DOWNLOADING, 0, null));
		executor.execute(() -> downloadInternal(direction));
	}

	public synchronized boolean delete(TranslationModel.Direction direction) {
		if (getSnapshot(direction).state == State.DOWNLOADING) {
			return false;
		}
		TranslationController.getInstance().unload();
		File directory = TranslationModel.getModelDirectory(application, direction);
		IOUtils.deleteRecursive(directory);
		if (directory.exists()) {
			Snapshot snapshot = new Snapshot(State.ERROR, 0, "Cannot delete language package");
			snapshots.put(direction, snapshot);
			notifyListeners(direction, snapshot);
			return false;
		}
		snapshots.remove(direction);
		notifyListeners(direction, new Snapshot(State.NOT_INSTALLED, 0, null));
		return true;
	}

	private void downloadInternal(TranslationModel.Direction direction) {
		File root = TranslationModel.getRootDirectory(application);
		File destination = TranslationModel.getModelDirectory(application, direction);
		File staging = new File(root, direction.id + ".installing");
		try {
			IOUtils.deleteRecursive(staging);
			if (!staging.mkdirs() && !staging.isDirectory()) {
				throw new IOException("Cannot create model directory");
			}
			long downloaded = 0L;
			for (TranslationModel.FileSpec file : direction.files) {
				File compressed = new File(staging, file.outputName + ".gz.part");
				downloadFile(direction, file, compressed, downloaded);
				downloaded += file.compressedSize;
				if (!file.compressedSha256.equals(calculateSha256(compressed))) {
					throw new IOException("Compressed package checksum mismatch");
				}
				File output = new File(staging, file.outputName + ".part");
				try (InputStream input = new GZIPInputStream(new BufferedInputStream(new FileInputStream(compressed)));
						BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(output))) {
					byte[] buffer = new byte[65536];
					for (int count; (count = input.read(buffer)) >= 0;) {
						if (count > 0) {
							outputStream.write(buffer, 0, count);
						}
					}
				}
				if (output.length() != file.uncompressedSize ||
						!file.uncompressedSha256.equals(calculateSha256(output))) {
					throw new IOException("Language package checksum mismatch");
				}
				move(output, new File(staging, file.outputName), true);
				if (!compressed.delete()) {
					throw new IOException("Cannot remove temporary package file");
				}
			}
			try (FileOutputStream output = new FileOutputStream(new File(staging, "installed.marker"))) {
				output.write((direction.id + "\nMozilla Bergamot MPL-2.0\n")
						.getBytes(StandardCharsets.UTF_8));
			}
			IOUtils.deleteRecursive(destination);
			move(staging, destination, false);
			setSnapshot(direction, new Snapshot(State.INSTALLED, 100, null));
		} catch (Exception e) {
			IOUtils.deleteRecursive(staging);
			String message = e.getMessage();
			if (message == null || message.trim().isEmpty()) {
				message = e.getClass().getSimpleName();
			}
			setSnapshot(direction, new Snapshot(State.ERROR, 0, message));
		}
	}

	private void downloadFile(TranslationModel.Direction direction, TranslationModel.FileSpec file,
			File destination, long alreadyDownloaded) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(file.url).openConnection();
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(120000);
		connection.setInstanceFollowRedirects(true);
		connection.setRequestProperty("Accept-Encoding", "identity");
		connection.setRequestProperty("User-Agent", "Slooop-Offline-Translation/1");
		try {
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				throw new IOException("HTTP " + responseCode);
			}
			if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
				throw new IOException("Refusing an insecure package redirect");
			}
			long current = 0L;
			try (InputStream input = new BufferedInputStream(connection.getInputStream());
					BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
				byte[] buffer = new byte[65536];
				for (int count; (count = input.read(buffer)) >= 0;) {
					if (count > 0) {
						output.write(buffer, 0, count);
						current += count;
						int progress = (int) Math.min(99L,
								(alreadyDownloaded + current) * 100L / direction.compressedSize);
						updateProgress(direction, progress);
					}
				}
			}
			if (destination.length() != file.compressedSize) {
				throw new IOException("Unexpected language package size");
			}
		} finally {
			connection.disconnect();
		}
	}

	private static void move(File source, File destination, boolean replace) throws IOException {
		try {
			if (replace) {
				Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} else {
				Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (AtomicMoveNotSupportedException e) {
			if (replace) {
				Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(source.toPath(), destination.toPath());
			}
		}
	}

	private synchronized void updateProgress(TranslationModel.Direction direction, int progress) {
		Snapshot current = snapshots.get(direction);
		if (current == null || current.state != State.DOWNLOADING || current.progress == progress) {
			return;
		}
		setSnapshotLocked(direction, new Snapshot(State.DOWNLOADING, progress, null));
	}

	private synchronized void setSnapshot(TranslationModel.Direction direction, Snapshot snapshot) {
		setSnapshotLocked(direction, snapshot);
	}

	private void setSnapshotLocked(TranslationModel.Direction direction, Snapshot snapshot) {
		snapshots.put(direction, snapshot);
		notifyListeners(direction, snapshot);
	}

	private void notifyListeners(TranslationModel.Direction direction, Snapshot snapshot) {
		mainHandler.post(() -> {
			for (Listener listener : listeners) {
				listener.onTranslationModelChanged(direction, snapshot);
			}
		});
	}

	private static String calculateSha256(File file) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			byte[] buffer = new byte[65536];
			for (int count; (count = input.read(buffer)) >= 0;) {
				if (count > 0) {
					digest.update(buffer, 0, count);
				}
			}
		}
		StringBuilder builder = new StringBuilder(64);
		for (byte value : digest.digest()) {
			builder.append(String.format(Locale.US, "%02x", value & 0xff));
		}
		return builder.toString();
	}
}
