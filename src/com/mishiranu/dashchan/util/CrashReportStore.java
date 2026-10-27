package com.mishiranu.dashchan.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

/** Own private directory only. Atomic complete reports, durable offered markers, bounded retention. */
public final class CrashReportStore {
	private final File root;
	public CrashReportStore(File root) throws IOException {
		this.root = root.getCanonicalFile();
		if (!this.root.isDirectory() && !this.root.mkdirs()) throw new IOException("Report storage unavailable");
	}
	private File owned(String name) throws IOException {
		File file = new File(root, name);
		if (!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !root.equals(file.getParentFile())) {
			throw new IOException("Invalid report path");
		}
		return file;
	}
	public File resolve(String id) throws IOException {
		if (!CrashReportPolicy.reportId(id)) throw new IOException("Invalid report identity");
		File file = owned(id);
		return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ? file : null;
	}
	private void atomicWrite(String name, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > CrashReportPolicy.MAX_BYTES) throw new IOException("Report size limit");
		File target = owned(name), temporary = owned("tmp-" + UUID.randomUUID().toString().replace("-", ""));
		try {
			try (FileOutputStream stream = new FileOutputStream(temporary)) {
				stream.write(bytes); stream.getFD().sync();
			}
			Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally { Files.deleteIfExists(temporary.toPath()); }
	}
	public void write(String id, String text, long now) throws IOException {
		if (!CrashReportPolicy.reportId(id)) throw new IOException("Invalid report identity");
		if (resolve(id) == null) atomicWrite(id, text);
		prune(now);
	}
	public String read(String id) throws IOException {
		File file = resolve(id);
		if (file == null) throw new IOException("Report no longer available");
		return readBounded(file);
	}
	private String readBounded(File file) throws IOException {
		try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[2048]; int length;
			while ((length = input.read(buffer)) != -1) {
				if (output.size() + length > CrashReportPolicy.MAX_BYTES) throw new IOException("Report size limit");
				output.write(buffer, 0, length);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}
	public ArrayList<File> list() throws IOException {
		File[] files = root.listFiles();
		if (files == null) throw new IOException("Cannot list reports");
		ArrayList<File> reports = new ArrayList<>();
		for (File file : files) if (CrashReportPolicy.reportId(file.getName())) {
			File resolved = resolve(file.getName()); if (resolved != null) reports.add(resolved);
		}
		reports.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
		return reports;
	}
	public boolean offered(String id) throws IOException {
		if (!CrashReportPolicy.reportId(id)) throw new IOException("Invalid report identity");
		return Files.isRegularFile(owned(id + ".seen").toPath(), LinkOption.NOFOLLOW_LINKS);
	}
	public boolean markOffered(String id) throws IOException {
		if (resolve(id) == null) return false;
		atomicWrite(id + ".seen", "offered\n"); return true;
	}
	public void delete(String id) throws IOException {
		if (!CrashReportPolicy.reportId(id)) throw new IOException("Invalid report identity");
		Files.deleteIfExists(owned(id).toPath()); Files.deleteIfExists(owned(id + ".seen").toPath());
	}
	public boolean containsSession(String session) throws IOException {
		if (session == null || !session.matches("[a-f0-9]{32}")) return false;
		for (File file : list()) {
			String text = read(file.getName());
			if ((text.contains("\nKind: JAVA\n") || text.contains("\nKind: TEST_JAVA\n"))
					&& text.contains("\nSession: " + session + "\n")) return true;
		}
		return false;
	}
	public long cursor(long now) throws IOException {
		File file = owned("system-cursor");
		if (file.isFile()) try { long value = Long.parseLong(readBounded(file).trim()); if (value >= 0) return value; }
		catch (NumberFormatException ignored) { /* Fresh baseline, never import an older profile's history. */ }
		atomicWrite("system-cursor", Long.toString(now)); return now;
	}
	public void advanceCursor(long timestamp) throws IOException { atomicWrite("system-cursor", Long.toString(timestamp)); }
	public void prune(long now) throws IOException {
		ArrayList<File> reports = list();
		for (int i = 0; i < reports.size(); i++) {
			File file = reports.get(i);
			if (i >= CrashReportPolicy.MAX_REPORTS || now > file.lastModified()
					&& now - file.lastModified() > CrashReportPolicy.MAX_AGE) delete(file.getName());
		}
		File[] files = root.listFiles();
		if (files == null) return;
		for (File file : files) {
			String name = file.getName();
			if (name.matches("tmp-[a-f0-9]{32}") && now - file.lastModified() > 60_000) Files.deleteIfExists(owned(name).toPath());
			if (name.endsWith(".seen")) {
				String id = name.substring(0, name.length() - 5);
				if (CrashReportPolicy.reportId(id) && resolve(id) == null) Files.deleteIfExists(owned(name).toPath());
			}
		}
	}
}
