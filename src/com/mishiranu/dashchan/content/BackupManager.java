package com.mishiranu.dashchan.content;

import android.content.Context;
import android.util.Pair;
import chan.util.DataFile;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.content.storage.AutohideStorage;
import com.mishiranu.dashchan.content.storage.CombinedFeedStorage;
import com.mishiranu.dashchan.content.storage.FavoritesStorage;
import com.mishiranu.dashchan.content.storage.StatisticsStorage;
import com.mishiranu.dashchan.content.storage.StorageManager;
import com.mishiranu.dashchan.content.storage.ThemesStorage;
import com.mishiranu.dashchan.util.IOUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BackupManager {
	private static final String FILE_NAME_PREFIX = "backup-";
	private static final String FILE_NAME_SUFFIX = ".zip";

	private static final String BACKUP_VERSION_0 = "dashchan:0";
	private static final String BACKUP_VERSION_1 = "dashchan:1";
	private static final int MAX_ARCHIVE_ENTRIES = 64;
	private static final long MAX_ENTRY_SIZE = 512L * 1024L * 1024L;
	private static final long MAX_TOTAL_SIZE = 1024L * 1024L * 1024L;
	private static final long MAX_COMPRESSION_RATIO = 200L;

	public static class BackupFile implements Comparable<BackupFile> {
		public final DataFile file;
		public final String name;
		public final long date;

		public BackupFile(DataFile file, String name, long date) {
			this.file = file;
			this.name = name;
			this.date = date;
		}

		@Override
		public int compareTo(BackupFile o) {
			return Long.compare(o.date, date);
		}
	}

	private static class Restore {
		public final boolean test;
		public final InputStream input;
		public String version;

		public Restore(boolean test, InputStream input) {
			this.test = test;
			this.input = input;
		}
	}

	static class ArchiveLimits {
		public long total;
	}

	private static void checkInterrupted() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup operation cancelled");
	}

	static class LimitedEntryInputStream extends InputStream {
		private final ZipInputStream input;
		private final ArchiveLimits limits;
		private long entry;

		public LimitedEntryInputStream(ZipInputStream input, ArchiveLimits limits) {
			this.input = input;
			this.limits = limits;
		}

		private void checkAvailable() throws IOException {
			checkInterrupted();
			if (entry >= MAX_ENTRY_SIZE || limits.total >= MAX_TOTAL_SIZE) {
				if (input.read() < 0) return;
				throw new IOException("Backup archive is too large");
			}
		}

		@Override
		public int read() throws IOException {
			checkAvailable();
			if (entry >= MAX_ENTRY_SIZE || limits.total >= MAX_TOTAL_SIZE) return -1;
			int value = input.read();
			if (value >= 0) {
				entry++;
				limits.total++;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (length == 0) return 0;
			checkAvailable();
			long remaining = Math.min(MAX_ENTRY_SIZE - entry, MAX_TOTAL_SIZE - limits.total);
			if (remaining <= 0) return -1;
			int count = input.read(buffer, offset, (int) Math.min(length, remaining));
			if (count > 0) {
				entry += count;
				limits.total += count;
			}
			return count;
		}

		@Override
		public void close() {
			// The owning ZipInputStream advances and closes entries explicitly.
		}

		public long getEntrySize() {
			return entry;
		}
	}

	static void drainEntry(LimitedEntryInputStream input) throws IOException {
		byte[] buffer = new byte[8192];
		while (input.read(buffer) >= 0) {
			// Keep all reads through the limiter, including EOF/CRC and cancellation checks.
		}
	}

	private static class InterruptibleOutputStream extends FilterOutputStream {
		public InterruptibleOutputStream(OutputStream output) {
			super(output);
		}

		@Override
		public void write(int value) throws IOException {
			checkInterrupted();
			super.write(value);
		}

		@Override
		public void write(byte[] buffer, int offset, int length) throws IOException {
			checkInterrupted();
			out.write(buffer, offset, length);
		}
	}

	private interface Writer {
		void write(OutputStream output) throws IOException;
	}

	private interface Reader {
		void read(Restore restore) throws IOException;

		default void cleanup() {}
	}

	private static void checkCompressionRatio(ZipEntry entry, LimitedEntryInputStream input)
			throws IOException {
		long compressed = entry.getCompressedSize();
		if (compressed > 0L && input.getEntrySize() / compressed > MAX_COMPRESSION_RATIO) {
			throw new IOException("Backup entry compression ratio is too high");
		}
	}

	private static boolean hasExcessiveDeclaredCompressionRatio(ZipEntry entry) {
		long size = entry.getSize();
		long compressed = entry.getCompressedSize();
		return size > 0L && compressed > 0L && size / compressed > MAX_COMPRESSION_RATIO;
	}

	private static class FileWriter implements Writer {
		private final File file;

		public FileWriter(File file) {
			this.file = file;
		}

		@Override
		public void write(OutputStream output) throws IOException {
			try (FileInputStream input = new FileInputStream(file)) {
				IOUtils.copyStream(input, output);
			}
		}
	}

	private static class FileReader implements Reader {
		private final File file;

		public FileReader(File file) {
			this.file = file;
		}

		@Override
		public void read(Restore restore) throws IOException {
			if (!restore.test) {
				try (FileOutputStream output = new FileOutputStream(file)) {
					IOUtils.copyStream(restore.input, output);
					output.getFD().sync();
				}
			}
		}

		@Override
		public void cleanup() {
			file.delete();
		}
	}

	private static class DatabaseReader implements Reader {
		@Override
		public void read(Restore restore) throws IOException {
			if (!restore.test) CommonDatabase.getInstance().readBackup(restore.input);
		}

		@Override
		public void cleanup() {
			CommonDatabase.getInstance().clearRestoreBackup();
		}
	}

	public enum Entry {
		VERSION(0, "version", Collections.emptyList(),
				output -> output.write((BACKUP_VERSION_1 + "\n").getBytes(StandardCharsets.UTF_8)), restore -> {
			restore.version = null;
			byte[] data = new byte[1024];
			int count = 0;
			while (count < data.length) {
				int read = restore.input.read(data, count, data.length - count);
				if (read < 0) break;
				count += read;
			}
			if (count <= 0 || count == data.length) {
				throw new IOException("Invalid version file");
			}
			restore.version = new String(data, 0, count, StandardCharsets.UTF_8).trim();
		}),
		DATABASE(R.string.database, "common.db", Collections.singletonList(BACKUP_VERSION_1),
				CommonDatabase.getInstance()::writeBackup, new DatabaseReader()),
		PREFERENCES_0(R.string.preferences, "com.mishiranu.dashchan_preferences.xml",
				Preferences.getFileForRestore(), Collections.singletonList(BACKUP_VERSION_0)),
		PREFERENCES_1(R.string.preferences, Preferences.getFilesForBackup(),
				Collections.singletonList(BACKUP_VERSION_1)),
		FAVORITES(R.string.favorites, FavoritesStorage.getInstance(),
				Arrays.asList(BACKUP_VERSION_0, BACKUP_VERSION_1)),
		AUTOHIDE(R.string.autohide, AutohideStorage.getInstance(),
				Arrays.asList(BACKUP_VERSION_0, BACKUP_VERSION_1)),
		COMBINED_FEEDS(R.string.combined_feeds, CombinedFeedStorage.getInstance(),
				Collections.singletonList(BACKUP_VERSION_1)),
		STATISTICS(R.string.statistics, StatisticsStorage.getInstance(),
				Arrays.asList(BACKUP_VERSION_0, BACKUP_VERSION_1)),
		THEMES(R.string.themes, ThemesStorage.getInstance(),
				Arrays.asList(BACKUP_VERSION_0, BACKUP_VERSION_1));

		public final int titleResId;
		private final String name;
		private final Writer writer;
		private final Reader reader;
		private final Set<String> versions;
		private StorageManager.Storage<?> storage;

		Entry(int titleResId, StorageManager.Storage<?> storage, Collection<String> versions) {
			this(titleResId, storage.getFilesForBackup(), versions);
			this.storage = storage;
		}

		Entry(int titleResId, Pair<File, File> backupFiles, Collection<String> versions) {
			this(titleResId, backupFiles.first.getName(), versions,
					new FileWriter(backupFiles.first), new FileReader(backupFiles.second));
		}

		Entry(int titleResId, String name, File restoreFile, Collection<String> versions) {
			this(titleResId, name, versions, null, new FileReader(restoreFile));
		}

		Entry(int titleResId, String name, Collection<String> versions, Writer writer, Reader reader) {
			this.titleResId = titleResId;
			this.name = name;
			this.writer = writer;
			this.reader = reader;
			this.versions = Collections.unmodifiableSet(new HashSet<>(versions));
		}

		private static Entry find(String name) {
			for (Entry entry : Entry.values()) {
				if (entry.name.equals(name)) {
					return entry;
				}
			}
			return null;
		}
	}

	public static List<BackupFile> getAvailableBackups(Context context) {
		DataFile root = DataFile.obtain(DataFile.Target.DOWNLOADS, null);
		List<DataFile> files = root.getChildren();
		List<BackupFile> backupFiles = new ArrayList<>();
		if (files != null) {
			DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
			DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(context);
			for (DataFile file : files) {
				String name = file.getName();
				if (name.startsWith(FILE_NAME_PREFIX) && name.endsWith(FILE_NAME_SUFFIX)) {
					name = name.substring(FILE_NAME_PREFIX.length(), name.length() - FILE_NAME_SUFFIX.length());
					long date;
					try {
						date = Long.parseLong(name);
					} catch (NumberFormatException e) {
						date = -1;
					}
					if (date >= 0) {
						name = dateFormat.format(date) + " " + timeFormat.format(date);
						backupFiles.add(new BackupFile(file, name, date));
					}
				}
			}
		}
		Collections.sort(backupFiles);
		return backupFiles;
	}

	public static InputStream makeBackup(Context context) {
		File backupFile = new File(context.getCacheDir(), "backup-" + UUID.randomUUID());
		boolean success = false;
		try {
			// UI-owned collections are cloned together without disk I/O or waiting for the writer.
			EnumMap<Entry, StorageManager.BackupSnapshot> snapshots = ConcurrentUtils.mainGet(() -> {
				EnumMap<Entry, StorageManager.BackupSnapshot> result = new EnumMap<>(Entry.class);
				for (Entry entry : Entry.values()) {
					if (entry.storage != null) result.put(entry, entry.storage.snapshotForBackup());
				}
				PreferencesBackup preferences = new PreferencesBackup(Preferences.PREFERENCES.getAll());
				result.put(Entry.PREFERENCES_1, preferences::write);
				return result;
			});
			BackupArchive.write(new FileOutputStream(backupFile), zip -> {
				OutputStream output = new InterruptibleOutputStream(zip);
				for (Entry entry : Entry.values()) {
					if (entry.writer != null) {
						zip.putNextEntry(new ZipEntry(entry.name));
						try {
							StorageManager.BackupSnapshot snapshot = snapshots.get(entry);
							if (snapshot != null) snapshot.write(output);
							else entry.writer.write(output);
						} finally {
							zip.closeEntry();
						}
					}
				}
			});
			// Only a successfully closed ZIP is eligible for export.
			success = true;
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
		} finally {
			if (!success) {
				backupFile.delete();
			}
		}
		FileInputStream input = null;
		if (success) {
			try {
				input = new FileInputStream(backupFile);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		// FileInputStream holds a file descriptor
		backupFile.delete();
		return success ? input : null;
	}

	public static void saveBackup(DownloadService.Binder binder, InputStream input) {
		binder.downloadStorage(input, null, null, null, null,
				FILE_NAME_PREFIX + System.currentTimeMillis() + FILE_NAME_SUFFIX, false, false);
	}

	public static List<Entry> readBackupEntries(DataFile file) {
		String version = BACKUP_VERSION_0;
		HashSet<Entry> entries = new HashSet<>();
		HashSet<String> names = new HashSet<>();
		ArchiveLimits limits = new ArchiveLimits();
		try (ZipInputStream zip = new ZipInputStream(file.openInputStream())) {
			ZipEntry zipEntry;
			int count = 0;
			while ((zipEntry = zip.getNextEntry()) != null) {
				boolean consumed = false;
				try {
					if (++count > MAX_ARCHIVE_ENTRIES || !names.add(zipEntry.getName())
							|| zipEntry.getSize() > MAX_ENTRY_SIZE
							|| hasExcessiveDeclaredCompressionRatio(zipEntry)) {
						throw new IOException("Invalid backup archive");
					}
					LimitedEntryInputStream input = new LimitedEntryInputStream(zip, limits);
					Entry entry = Entry.find(zipEntry.getName());
					if (entry != null) {
						Restore restore = new Restore(true, input);
						restore.version = version;
						entry.reader.read(restore);
						version = restore.version;
						entries.add(entry);
					}
					drainEntry(input);
					checkCompressionRatio(zipEntry, input);
					consumed = true;
				} finally {
					if (consumed) zip.closeEntry();
				}
			}
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			entries.clear();
		}
		ArrayList<Entry> result = new ArrayList<>();
		for (Entry entry : Entry.values()) {
			if (entries.contains(entry)) {
				if (entry.versions.contains(version)) {
					result.add(entry);
				}
			}
		}
		return result;
	}

	public static boolean loadBackup(DataFile file, Collection<Entry> entries) {
		List<Entry> available = readBackupEntries(file);
		if (entries.isEmpty() || !available.containsAll(entries)) return false;
		boolean success = false;
		HashSet<Entry> restored = new HashSet<>();
		HashSet<String> names = new HashSet<>();
		ArchiveLimits limits = new ArchiveLimits();
		try (ZipInputStream zip = new ZipInputStream(file.openInputStream())) {
			ZipEntry zipEntry;
			int count = 0;
			while ((zipEntry = zip.getNextEntry()) != null) {
				boolean consumed = false;
				try {
					if (++count > MAX_ARCHIVE_ENTRIES || !names.add(zipEntry.getName())
							|| zipEntry.getSize() > MAX_ENTRY_SIZE
							|| hasExcessiveDeclaredCompressionRatio(zipEntry)) {
						throw new IOException("Invalid backup archive");
					}
					LimitedEntryInputStream input = new LimitedEntryInputStream(zip, limits);
					Entry entry = Entry.find(zipEntry.getName());
					if (entry != null && entries.contains(entry)) {
						entry.reader.read(new Restore(false, input));
						restored.add(entry);
					}
					drainEntry(input);
					checkCompressionRatio(zipEntry, input);
					consumed = true;
				} finally {
					if (consumed) zip.closeEntry();
				}
			}
			success = restored.containsAll(entries);
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			success = false;
		}
		if (!success) {
			for (Entry entry : entries) entry.reader.cleanup();
		}
		return success;
	}
}
