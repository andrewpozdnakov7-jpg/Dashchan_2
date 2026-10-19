package com.mishiranu.dashchan.content.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import chan.content.ChanManager;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.FileProvider;
import com.mishiranu.dashchan.content.UpdaterActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A private durable receipt, recorded before downloading. Never trusts an APK just by its name. */
public final class DownloadedUpdate {
	private static final String STORE = "downloaded-update";
	private static final String PROCESS = UUID.randomUUID().toString();
	private DownloadedUpdate() {}
	public enum Check { READY, MISSING, INVALID, INSTALLED }
	public static final class Entry {
		public final String id, file, version;
		public final long code, length;
		final byte[] sha256;
		final ChanManager.Fingerprints fingerprints;
		final JSONObject state;
		Entry(JSONObject state) throws JSONException {
			this.state = state;
			id = state.getString("id"); file = state.getString("file"); version = state.getString("version");
			code = state.getLong("code"); length = state.getLong("length");
			String hash = state.getString("sha256");
			if (!hash.matches("[0-9a-f]{64}") || code <= 0 || length <= 0
					|| !file.equals("client-" + version + ".apk") || file.indexOf('/') >= 0 || file.indexOf('\\') >= 0) {
				throw new JSONException("Invalid update receipt");
			}
			sha256 = new byte[32];
			for (int i = 0; i < sha256.length; i++) sha256[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
			HashSet<String> signatures = new HashSet<>();
			JSONArray array = state.getJSONArray("fingerprints");
			for (int i = 0; i < array.length(); i++) signatures.add(array.getString(i));
			if (signatures.isEmpty()) throw new JSONException("Missing update signer");
			fingerprints = new ChanManager.Fingerprints(signatures);
		}
	}
	private static SharedPreferences store(Context context) {
		return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
	}
	public static synchronized Entry read(Context context) {
		if (!BuildConfig.ALLOW_APPLICATION_SELF_UPDATE) return null;
		try {
			String value = store(context).getString("receipt", null);
			return value != null ? new Entry(new JSONObject(value)) : null;
		}
		catch (JSONException | RuntimeException e) { return null; }
	}
	private static void write(Context context, JSONObject state) throws IOException {
		if (!store(context).edit().putString("receipt", state.toString()).commit()) throw new IOException("Cannot save update receipt");
	}
	public static synchronized Entry remember(Context context, UpdaterActivity.Request request) throws IOException {
		if (request.sha256sum == null || request.sha256sum.length != 32 || request.versionCode <= 0 || request.length <= 0) {
			throw new IOException("Incomplete client update metadata");
		}
		try {
			StringBuilder hash = new StringBuilder(64);
			for (byte b : request.sha256sum) hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
			String file = "client-" + request.versionName + ".apk";
			Entry previous = read(context);
			ChanManager.Fingerprints signatures = request.checkFingerprints != null ? request.checkFingerprints
					: ChanManager.getInstance().getApplicationFingerprints();
			if (previous != null && previous.file.equals(file) && previous.code == request.versionCode
					&& previous.length == request.length && Arrays.equals(previous.sha256, request.sha256sum)
					&& previous.fingerprints.equals(signatures)) return previous;
			JSONObject state = new JSONObject().put("id", UUID.randomUUID().toString()).put("file", file)
					.put("version", request.versionName).put("code", request.versionCode).put("length", request.length)
					.put("sha256", hash.toString()).put("fingerprints", new JSONArray(signatures.fingerprints))
					.put("launches", 0).put("next_launch", 0).put("postponements", 0).put("last_launch", PROCESS);
			Entry entry = new Entry(state);
			write(context, state);
			return entry;
		} catch (JSONException e) { throw new IOException("Invalid client update metadata", e); }
	}
	public static synchronized void launch(Context context) throws IOException {
		Entry entry = read(context);
		if (entry == null) return;
		try {
			long count = UpdateReminderPolicy.countLaunch(entry.state.optLong("launches"),
					entry.state.optString("last_launch"), PROCESS);
			if (!PROCESS.equals(entry.state.optString("last_launch"))) {
				write(context, entry.state.put("launches", count).put("last_launch", PROCESS));
			}
		} catch (JSONException e) { throw new IOException(e); }
	}
	public static boolean due(Entry entry) {
		return entry != null && !PROCESS.equals(entry.state.optString("offered"))
				&& UpdateReminderPolicy.due(entry.state.optLong("launches"), entry.state.optLong("next_launch"));
	}
	public static synchronized boolean claim(Context context, String id) throws IOException {
		Entry entry = read(context);
		if (entry == null || !entry.id.equals(id) || !due(entry)) return false;
		try { write(context, entry.state.put("offered", PROCESS)); return true; }
		catch (JSONException e) { throw new IOException(e); }
	}
	public static synchronized boolean postpone(Context context, String id) throws IOException {
		Entry entry = read(context);
		if (entry == null || !entry.id.equals(id)) return false;
		try {
			int times = entry.state.optInt("postponements");
			write(context, entry.state.put("next_launch", UpdateReminderPolicy.nextLaunch(entry.state.optLong("launches"), times))
					.put("postponements", Math.min(2, times + 1)).put("offered", PROCESS).put("snoozed_process", PROCESS));
			return true;
		} catch (JSONException e) { throw new IOException(e); }
	}
	/** Cancelling the system installer is treated as Later; recovery/retries in the same launch do not advance again. */
	public static synchronized boolean beginInstallation(Context context, String id) throws IOException {
		Entry entry = read(context);
		return entry != null && entry.id.equals(id)
				&& (PROCESS.equals(entry.state.optString("snoozed_process")) || postpone(context, id));
	}
	public static synchronized boolean current(Context context, String id) {
		Entry entry = read(context); return entry != null && entry.id.equals(id);
	}
	/** Verify the actual bytes copied into PackageInstaller, not only a previous view of the cache file. */
	public static void copyVerified(Entry entry, FileInputStream input, OutputStream output) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[65536]; int count; long length = 0;
			while ((count = input.read(buffer)) != -1) {
				if (count > entry.length - length) throw new IOException("Update grew while copying");
				output.write(buffer, 0, count); digest.update(buffer, 0, count); length += count;
			}
			if (length != entry.length || !MessageDigest.isEqual(entry.sha256, digest.digest())) throw new IOException("Update changed while copying");
		} catch (NoSuchAlgorithmException e) { throw new IOException(e); }
	}
	public static synchronized void clear(Context context, String id) throws IOException {
		Entry entry = read(context);
		if (entry != null && entry.id.equals(id) && !store(context).edit().clear().commit()) {
			throw new IOException("Cannot clear update receipt");
		}
	}
	/** Worker thread only. Recheck digest and both the expected and installed package signer. */
	public static Check check(Context context, Entry entry) {
		if (entry.code <= BuildConfig.VERSION_CODE) return Check.INSTALLED;
		File file = FileProvider.getUpdatesFile(entry.file);
		if (file == null || !file.isFile()) return Check.MISSING;
		if (file.length() != entry.length) return Check.INVALID;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (FileInputStream input = new FileInputStream(file)) {
				byte[] buffer = new byte[65536]; int count; long length = 0;
				while ((count = input.read(buffer)) != -1) {
					if (count > entry.length - length) return Check.INVALID;
					digest.update(buffer, 0, count); length += count;
				}
				if (length != entry.length) return Check.INVALID;
			}
			if (!MessageDigest.isEqual(entry.sha256, digest.digest())) return Check.INVALID;
			PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
			if (info == null || !context.getPackageName().equals(info.packageName) || info.getLongVersionCode() != entry.code
					|| !entry.version.equals(info.versionName) || info.applicationInfo == null
					|| (info.applicationInfo.flags & (android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
							| android.content.pm.ApplicationInfo.FLAG_TEST_ONLY)) != 0) return Check.INVALID;
			ChanManager.Fingerprints actual = ChanManager.getInstance().getFingerprints(file);
			return entry.fingerprints.equals(actual) && ChanManager.getInstance().getApplicationFingerprints().equals(actual)
					? Check.READY : Check.INVALID;
		} catch (IOException | NoSuchAlgorithmException | RuntimeException e) { return Check.INVALID; }
	}
}
