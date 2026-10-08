package com.mishiranu.dashchan.ui;

import android.os.Bundle;
import android.os.Parcel;
import android.util.AtomicFile;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Supplier;

/** Existing synchronous page-state storage; no scheduling or navigation policy. */
final class PageSessionStore {
	static final String EXTRA_PAGES_STATE_VERSION = "pagesStateVersion";
	static final int PAGES_STATE_VERSION = 1;
	private final Supplier<File> filesDirectory;
	private final Supplier<File> savedPagesFile;
	private final ClassLoader classLoader;

	PageSessionStore(Supplier<File> filesDirectory, Supplier<File> savedPagesFile, ClassLoader classLoader) {
		this.filesDirectory = filesDirectory;
		this.savedPagesFile = savedPagesFile;
		this.classLoader = classLoader;
	}

	File getSavedPagesFile() {
		return savedPagesFile.get();
	}

	File getPagesSessionFile() {
		return new File(filesDirectory.get(), "pages-session");
	}

	File getPagesInstanceStateFile() {
		return new File(filesDirectory.get(), "pages-instance-state");
	}

	Bundle readPagesInstanceStateOrFallback(Bundle fallback) {
		Bundle external = readPagesState(getPagesInstanceStateFile(), true, true);
		// The framework Bundle still carries the current page when the external stack is damaged.
		return external != null ? external : fallback;
	}

	Bundle readPagesState(File file, boolean deleteAfterRead, boolean checkVersion) {
		if (file == null) {
			return null;
		}
		Parcel parcel = Parcel.obtain();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AtomicFile atomicFile = new AtomicFile(file);
		boolean success = false;
		try (FileInputStream input = atomicFile.openRead()) {
			IOUtils.copyStream(input, output);
			byte[] data = output.toByteArray();
			parcel.unmarshall(data, 0, data.length);
			parcel.setDataPosition(0);
			Bundle bundle = new Bundle();
			bundle.setClassLoader(classLoader);
			bundle.readFromParcel(parcel);
			if (checkVersion && bundle.getInt(EXTRA_PAGES_STATE_VERSION, 0) != PAGES_STATE_VERSION) {
				return null;
			}
			success = true;
			return bundle;
		} catch (IOException | RuntimeException e) {
			return null;
		} finally {
			parcel.recycle();
			if (deleteAfterRead || !success) {
				atomicFile.delete();
			}
		}
	}

	static void deletePagesState(File file) {
		if (file != null) {
			new AtomicFile(file).delete();
		}
	}

	boolean writePagesState(File file, Bundle state) {
		Parcel parcel = Parcel.obtain();
		AtomicFile atomicFile = new AtomicFile(file);
		FileOutputStream output = null;
		try {
			state.writeToParcel(parcel, 0);
			byte[] data = parcel.marshall();
			output = atomicFile.startWrite();
			output.write(data);
			atomicFile.finishWrite(output);
			return true;
		} catch (IOException | RuntimeException e) {
			if (output != null) {
				atomicFile.failWrite(output);
			}
			return false;
		} finally {
			parcel.recycle();
		}
	}
}
