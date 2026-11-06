package com.mishiranu.dashchan.content.update;

import android.util.AtomicFile;
import com.mishiranu.dashchan.content.MainApplication;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/** Private receipt for the bytes accepted by the downloader, independent of the external APK cache. */
public final class DownloadedApk {
	private DownloadedApk() {}
	private static MessageDigest digest() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	private static AtomicFile receipt(File apk) throws IOException {
		File directory = new File(MainApplication.getInstance().getCacheDir(), "verified-apks");
		if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create APK receipt cache");
		StringBuilder key = new StringBuilder();
		for (byte b : digest().digest(apk.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
			key.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
		}
		return new AtomicFile(new File(directory, key + ".receipt"));
	}
	public static void invalidate(File apk) throws IOException { receipt(apk).delete(); }
	public static void record(File apk, byte[] sha256) throws IOException {
		if (sha256 == null || sha256.length != 32 || !apk.isFile() || apk.length() <= 0) {
			throw new IOException("Invalid downloaded APK");
		}
		AtomicFile receipt = receipt(apk);
		FileOutputStream stream = receipt.startWrite();
		try {
			DataOutputStream output = new DataOutputStream(stream);
			output.writeLong(apk.length());
			output.write(sha256);
			output.flush();
			receipt.finishWrite(stream);
		} catch (IOException | RuntimeException e) {
			receipt.failWrite(stream);
			throw e;
		}
	}
	/** Hash exactly the bytes written to the install session; never commit a partial/mutated APK. */
	public static void copyVerified(File apk, FileInputStream input, OutputStream output,
			BooleanSupplier cancelled) throws IOException {
		long length;
		byte[] expected = new byte[32];
		try (DataInputStream receipt = new DataInputStream(receipt(apk).openRead())) {
			length = receipt.readLong();
			receipt.readFully(expected);
			if (length <= 0 || receipt.read() != -1) throw new IOException("Invalid APK receipt");
		}
		MessageDigest digest = digest();
		byte[] buffer = new byte[65536];
		long copied = 0;
		int count;
		while ((count = input.read(buffer)) != -1) {
			if (cancelled.getAsBoolean()) throw new IOException("APK installation cancelled");
			if (count > length - copied) throw new IOException("Downloaded APK grew");
			output.write(buffer, 0, count);
			digest.update(buffer, 0, count);
			copied += count;
		}
		if (copied != length || !MessageDigest.isEqual(expected, digest.digest())) {
			throw new IOException("Downloaded APK changed before installation");
		}
	}
}
