package com.mishiranu.dashchan.content.storage;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class StorageBackupSnapshotTest {
	// Uses the actual Storage snapshot API; no Handler/disk initialization or Android shims.
	private static final class Model extends StorageManager.Storage<List<String>> {
		final List<String> values = new ArrayList<>();
		Model() { super("test", 1000, 1000); }
		@Override public List<String> onClone() { return new ArrayList<>(values); }
		@Override public void onRead(InputStream input) {}
		@Override public void onWrite(List<String> snapshot, OutputStream output) throws IOException {
			output.write(String.join(",", snapshot).getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test public void capturesUnsavedChangesAndIgnoresLaterChanges() throws Exception {
		Model model = new Model();
		model.values.add("unsaved");
		StorageManager.BackupSnapshot snapshot = model.snapshotForBackup();
		model.values.clear();
		model.values.add("later");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		snapshot.write(output);
		assertEquals("unsaved", output.toString("UTF-8"));
	}

	@Test public void exportFailureIsNotSwallowed() throws Exception {
		Model model = new Model();
		model.values.add("value");
		try {
			model.snapshotForBackup().write(new OutputStream() {
				@Override public void write(int value) throws IOException { throw new IOException("export"); }
			});
			fail("Must fail the backup");
		} catch (IOException expected) { assertEquals("export", expected.getMessage()); }
	}
}
