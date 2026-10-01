package com.mishiranu.dashchan.ui.gallery;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.AtomicFile;
import android.util.Log;
import chan.content.Chan;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Private, disposable cache. A Bundle holds only its random key and current selection. */
final class GalleryRestoreStore {
	private static final Executor WORKER = ConcurrentUtils.newSingleThreadPool(3000, "GalleryRestore", null);
	private static final long MAX_TOTAL = 32L * 1024 * 1024, MAX_AGE = 7L * 24 * 60 * 60 * 1000;

	static GalleryRestoreCodec.Item describe(GalleryItem item, Chan chan) {
		Uri uri = item.getFileUri(chan), thumbnail = item.getThumbnailUri(chan);
		return new GalleryRestoreCodec.Item(uri != null ? uri.toString() : null,
				thumbnail != null ? thumbnail.toString() : null, item.getFileName(chan), item.boardName,
				item.threadNumber, item.postNumber != null ? item.postNumber.toString() : null,
				item.originalName, item.width, item.height, item.size);
	}
	private static File directory() throws IOException {
		File directory = new File(MainApplication.getInstance().getCacheDir(), "gallery-restore");
		if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache unavailable");
		return directory;
	}
	static String save(String chanName, List<GalleryItem> source) {
		if (source.isEmpty() || source.size() > GalleryRestoreCodec.MAX_ITEMS) return null;
		String token = UUID.randomUUID().toString();
		List<GalleryItem> items;
		try (AuditDiagnostics.Scope copy = AuditDiagnostics.begin("Audit/Gallery/snapshotCopy").count(source.size())) {
			items = new ArrayList<>(source);
			copy.result("ok");
		}
		GalleryRestoreDiagnostics.Save audit = GalleryRestoreDiagnostics.register(token, items.size());
		try {
			WORKER.execute(() -> {
				audit.start();
				AtomicFile file = null;
				FileOutputStream output = null;
				try (AuditDiagnostics.Scope write = AuditDiagnostics.begin("Audit/Gallery/writeSnapshot").count(items.size())) {
					Chan chan = Chan.get(chanName);
					List<GalleryRestoreCodec.Item> descriptors = new ArrayList<>(items.size());
					try (AuditDiagnostics.Scope describe = AuditDiagnostics.begin("Audit/Gallery/descriptors").count(items.size())) {
						for (GalleryItem item : items) descriptors.add(describe(item, chan));
						describe.result("ok");
					}
					File directory = directory();
					file = new AtomicFile(new File(directory, token));
					output = file.startWrite();
					try (AuditDiagnostics.Scope encode = AuditDiagnostics.begin("Audit/Gallery/encode").count(items.size())) {
						GalleryRestoreCodec.write(output, new GalleryRestoreCodec.Snapshot(chanName, descriptors));
						encode.result("ok");
					}
					try (AuditDiagnostics.Scope sync = AuditDiagnostics.begin("Audit/Gallery/finishWrite")) {
						file.finishWrite(output);
						sync.result("ok");
					}
					output = null;
					audit.written();
					try (AuditDiagnostics.Scope prune = AuditDiagnostics.begin("Audit/Gallery/prune")) {
						prune(directory, token);
						prune.result("ok");
					}
					write.result("ok");
					Log.d("GalleryRestore", "snapshot_saved count=" + items.size());
				} catch (IOException | RuntimeException e) {
					if (file != null && output != null) file.failWrite(output);
					Log.w("GalleryRestore", "save_failed type=" + e.getClass().getSimpleName());
				} finally {
					audit.finish();
				}
			});
		} catch (RuntimeException | Error e) {
			audit.finish();
			throw e;
		}
		GalleryRestoreDiagnostics.observe("Audit/Gallery/tokenReturned", token);
		return token;
	}
	static void load(String token, String chanName, CancellationSignal signal, Consumer<List<GalleryItem>> callback) {
		GalleryRestoreDiagnostics.observe("Audit/Gallery/loadRequested", token);
		AuditDiagnostics.Scope queue = AuditDiagnostics.beginAsync("Audit/Gallery/loadQueue");
		try {
			WORKER.execute(() -> {
				queue.result("started"); queue.close();
				List<GalleryItem> items = new ArrayList<>();
				AuditDiagnostics.Scope load = AuditDiagnostics.begin("Audit/Gallery/loadSnapshot");
				try {
					signal.throwIfCanceled();
					if (!GalleryRestoreCodec.validToken(token)) throw new IOException("Invalid snapshot key");
					File file = new File(directory(), token);
					if (!file.isFile()) { load.result("missing"); throw new IOException("Cache unavailable"); }
					if (file.length() > GalleryRestoreCodec.MAX_BYTES) { load.result("oversize"); throw new IOException("Cache unavailable"); }
					GalleryRestoreCodec.Snapshot snapshot;
					try (FileInputStream input = new FileInputStream(file)) {
						snapshot = GalleryRestoreCodec.read(input, signal::isCanceled);
					}
					if (!Objects.equals(chanName, snapshot.chan())) throw new IOException("Wrong source");
					for (GalleryRestoreCodec.Item item : snapshot.items()) {
						signal.throwIfCanceled();
						items.add(GalleryItem.fromRestoreDescriptor(item.uri(), item.thumbnail(), item.fileName(),
								item.board(), item.thread(), item.post() != null ? PostNumber.parseOrThrow(item.post()) : null,
								item.originalName(), item.width(), item.height(), item.size()));
					}
					load.count(items.size());
					load.result("ok");
				} catch (IOException | RuntimeException e) {
					if (signal.isCanceled()) load.result("cancelled");
					items.clear();
					if (!signal.isCanceled()) Log.w("GalleryRestore", "load_failed type=" + e.getClass().getSimpleName());
				} finally {
					load.close();
				}
				if (signal.isCanceled()) return;
				List<GalleryItem> result = items;
				AuditDiagnostics.Scope delivery = AuditDiagnostics.beginAsync("Audit/Gallery/loadCallbackQueue");
				ConcurrentUtils.HANDLER.post(() -> {
					delivery.result(signal.isCanceled() ? "cancelled" : "delivered"); delivery.close();
					if (!signal.isCanceled()) callback.accept(result);
				});
			});
		} catch (RuntimeException | Error e) {
			queue.result("failed"); queue.close();
			throw e;
		}
	}
	private static void prune(File directory, String keep) {
		File[] files = directory.listFiles(file -> {
			String name = file.getName();
			if (name.endsWith(".new") || name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
			return file.isFile() && GalleryRestoreCodec.validToken(name);
		});
		if (files == null) return;
		Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
		long total = 0, now = System.currentTimeMillis();
		for (File file : files) {
			total += file.length();
			if (!keep.equals(file.getName()) && (total > MAX_TOTAL || now - file.lastModified() > MAX_AGE)) {
				if (!file.delete()) Log.w("GalleryRestore", "prune_failed");
			}
		}
	}
}
