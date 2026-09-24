package com.mishiranu.dashchan.content;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipOutputStream;

final class BackupArchive {
	interface Entries {
		void write(ZipOutputStream zip) throws IOException;
	}

	private BackupArchive() {}

	/** Owns output. A successful return includes writing the central directory and closing output. */
	static void write(OutputStream output, Entries entries) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			entries.write(zip);
		}
	}
}
