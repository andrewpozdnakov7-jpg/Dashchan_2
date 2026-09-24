package com.mishiranu.dashchan.content.storage;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Pair;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.Logger;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import org.json.JSONException;
import org.json.JSONObject;

public class StorageManager implements Handler.Callback {
	private static final StorageManager INSTANCE = new StorageManager();

	public static StorageManager getInstance() {
		return INSTANCE;
	}

	private StorageManager() {}

	private final Handler handler = new Handler(Looper.getMainLooper(), this);
	private final StorageWriteQueue writer = new StorageWriteQueue(e ->
			Logger.write(Logger.Type.ERROR, "StorageManager", "write_failed", e.getClass().getSimpleName()));
	private static final int MESSAGE_SERIALIZE = 1;

	private <Data> void performSerialize(Storage<Data> storage, Data data) throws IOException {
		// The writer only uses the cloned data and the file lock, never the model monitor.
		synchronized (storage.lock) {
			StorageFile.write(getFile(storage), getBackupFile(storage), output -> storage.onWrite(data, output));
		}
	}

	private static final class PendingSerialize {
		final Storage<?> storage;
		final long firstScheduled;

		PendingSerialize(Storage<?> storage, long firstScheduled) {
			this.storage = storage;
			this.firstScheduled = firstScheduled;
		}
	}

	public static abstract class Storage<Data> {
		private final String name;
		private final int timeout;
		private final int maxTimeout;

		private final Object lock = new Object();
		// Guarded by this storage's monitor, also used by synchronized model mutators/onClone.
		private PendingSerialize pending;
		private StorageWriteQueue.Ticket lastWrite;

		public Storage(String name, int timeout, int maxTimeout) {
			this.name = name;
			this.timeout = timeout;
			this.maxTimeout = maxTimeout;
		}

		protected void startRead() {
			try (InputStream input = INSTANCE.open(this)) {
				onRead(input);
			} catch (FileNotFoundException e) {
				// Ignore exception
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		public final Pair<File, File> getFilesForBackup() {
			File storage = INSTANCE.getFile(this);
			File restore = INSTANCE.getRestoreFile(this);
			return new Pair<>(storage, restore);
		}

		public final void serialize() {
			INSTANCE.serialize(this);
		}

		public final void await(boolean async) {
			INSTANCE.await(this, async);
		}

		/** Capture on the model/UI thread; serialize the detached snapshot on the backup worker. */
		public final synchronized BackupSnapshot snapshotForBackup() {
			Data data = onClone();
			return output -> onWrite(data, output);
		}

		/** Background callers only: wait for the current snapshot and report actual write success. */
		public final boolean awaitSaved() {
			return INSTANCE.awaitSaved(this);
		}

		public abstract Data onClone();
		public abstract void onRead(InputStream input) throws IOException;
		public abstract void onWrite(Data data, OutputStream output) throws IOException;
	}

	public static abstract class JsonOrgStorage<Data> extends Storage<Data> {
		@SuppressWarnings("CharsetObjectCanBeUsed")
		private static final Charset CHARSET = Charset.forName("UTF-8");

		public JsonOrgStorage(String name, int timeout, int maxTimeout) {
			super(name, timeout, maxTimeout);
		}

		@Override
		public final void onRead(InputStream input) throws IOException {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			IOUtils.copyStream(input, output);
			try {
				onDeserialize(new JSONObject(new String(output.toByteArray(), CHARSET)));
			} catch (JSONException e) {
				// Ignore exception
			}
		}

		@Override
		public final void onWrite(Data data, OutputStream output) throws IOException {
			JSONObject jsonObject;
			try {
				jsonObject = onSerialize(data);
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			if (jsonObject != null) {
				output.write(jsonObject.toString().getBytes(CHARSET));
			}
		}

		public abstract void onDeserialize(JSONObject jsonObject) throws JSONException;
		public abstract JSONObject onSerialize(Data data) throws JSONException;
	}

	private File getDirectory() {
		File file = new File(MainApplication.getInstance().getFilesDir(), "storage");
		file.mkdirs();
		return file;
	}

	private File getFile(String name) {
		return new File(getDirectory(), name + ".json");
	}

	private File getFile(Storage<?> storage) {
		return getFile(storage.name);
	}

	private File getBackupFile(Storage<?> storage) {
		return getFile(storage.name + ".backup");
	}

	private File getRestoreFile(Storage<?> storage) {
		return getFile(storage.name + ".restore");
	}

	private InputStream open(Storage<?> storage) throws IOException {
		synchronized (storage.lock) {
			File file = getFile(storage);
			StorageFile.restore(getBackupFile(storage), file);
			StorageFile.restore(getRestoreFile(storage), file);
			return new FileInputStream(file);
		}
	}

	public interface BackupSnapshot {
		void write(OutputStream output) throws IOException;
	}

	private void serialize(Storage<?> storage) {
		synchronized (storage) {
			long now = SystemClock.elapsedRealtime();
			long firstScheduled = storage.pending != null ? storage.pending.firstScheduled : now;
			cancelPending(storage);
			PendingSerialize pending = new PendingSerialize(storage, firstScheduled);
			storage.pending = pending;
			long timeout = Math.min(storage.timeout, firstScheduled + storage.maxTimeout - now);
			if (timeout <= 0 || !handler.sendMessageDelayed(
					handler.obtainMessage(MESSAGE_SERIALIZE, pending), timeout)) {
				enqueueSerialize(storage);
			}
		}
	}

	public void await(Storage<?> storage, boolean async) {
		StorageWriteQueue.Ticket ticket = flush(storage);
		if (!async && ticket != null) {
			ticket.await();
		}
	}

	private boolean awaitSaved(Storage<?> storage) {
		StorageWriteQueue.Ticket ticket = flush(storage);
		return ticket == null || ticket.await();
	}

	private StorageWriteQueue.Ticket flush(Storage<?> storage) {
		synchronized (storage) {
			if (storage.pending != null || storage.lastWrite != null && storage.lastWrite.isFailed()) {
				enqueueSerialize(storage);
			}
			return storage.lastWrite;
		}
	}

	// Called only under the storage monitor. Clone and enqueue are one ordered operation.
	private <Data> void enqueueSerialize(Storage<Data> storage) {
		Data data = storage.onClone();
		storage.lastWrite = writer.enqueue(() -> performSerialize(storage, data));
		cancelPending(storage);
	}

	private void cancelPending(Storage<?> storage) {
		if (storage.pending != null) {
			handler.removeMessages(MESSAGE_SERIALIZE, storage.pending);
			storage.pending = null;
		}
	}

	@Override
	public boolean handleMessage(Message msg) {
		if (msg.what != MESSAGE_SERIALIZE || !(msg.obj instanceof PendingSerialize)) {
			return false;
		}
		PendingSerialize pending = (PendingSerialize) msg.obj;
		synchronized (pending.storage) {
			// A removed message may already have been dispatched before flush/reschedule.
			if (pending.storage.pending == pending) {
				enqueueSerialize(pending.storage);
			}
		}
		return true;
	}
}
