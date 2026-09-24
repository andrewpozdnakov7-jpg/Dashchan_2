package com.mishiranu.dashchan.content.storage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Keeps the existing JSON/backup format; success includes sync, close and backup removal. */
final class StorageFile {
	interface Writer {
		void write(OutputStream output) throws IOException;
	}

	private StorageFile() {}

	static void write(File file, File backup, Writer writer) throws IOException {
		if (backup.exists() && !backup.isFile()) {
			throw new IOException("Invalid storage backup");
		}
		if (file.exists()) {
			if (!backup.exists()) {
				if (!file.renameTo(backup)) {
					throw new IOException("Cannot create storage backup");
				}
			} else if (!file.delete()) {
				throw new IOException("Cannot remove incomplete storage file");
			}
		}
		boolean committed = false;
		try {
			try (FileOutputStream output = new FileOutputStream(file)) {
				writer.write(output);
				output.flush();
				output.getFD().sync();
			}
			if (backup.exists() && !backup.delete()) {
				// Otherwise the next launch would restore the old backup over this write.
				throw new IOException("Cannot commit storage backup");
			}
			committed = true;
		} finally {
			if (!committed && file.exists() && !file.delete()) {
				throw new IOException("Cannot remove failed storage write");
			}
		}
	}

	static void restore(File saved, File file) throws IOException {
		if (saved.exists()) {
			if (!saved.isFile()) {
				throw new IOException("Invalid storage recovery file");
			}
			if (file.exists() && !file.delete()) {
				throw new IOException("Cannot replace storage during recovery");
			}
			if (!saved.renameTo(file)) {
				throw new IOException("Cannot recover storage");
			}
		}
	}
}
