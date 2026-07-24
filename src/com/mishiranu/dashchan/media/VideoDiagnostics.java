package com.mishiranu.dashchan.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.SystemClock;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class VideoDiagnostics {
	private static final String DIRECTORY_NAME = "video-diagnostics";
	private static final String FILE_PREFIX = "video-diagnostics-";
	private static final int MAX_FILES = 3;
	private static final int UI_REPORT_LIMIT = 64 * 1024;

	private static final Object LOCK = new Object();
	private static boolean recording;
	private static long startedAt;
	private static long startedElapsedRealtime;
	private static final StringBuilder uiReport = new StringBuilder();
	private static boolean uiReportTruncated;
	private static File lastFile;

	private VideoDiagnostics() {}

	public static boolean isRecording() {
		synchronized (LOCK) {
			return recording;
		}
	}

	public static boolean start() {
		synchronized (LOCK) {
			if (recording) {
				return false;
			}
			recording = true;
			startedAt = System.currentTimeMillis();
			startedElapsedRealtime = SystemClock.elapsedRealtime();
			uiReport.setLength(0);
			uiReportTruncated = false;
			appendUiReportLocked("capture_started=true");
		}
		VideoPlayer.startDiagnosticCapture();
		return true;
	}

	public static File stop() {
		long sessionStartedAt;
		String uiReport;
		synchronized (LOCK) {
			if (!recording) {
				return getLastFileLocked();
			}
			appendUiReportLocked("capture_stopped=true");
			recording = false;
			sessionStartedAt = startedAt;
			startedAt = 0L;
			startedElapsedRealtime = 0L;
			uiReport = VideoDiagnostics.uiReport.toString();
		}
		String nativeReport = VideoPlayer.stopDiagnosticCapture();
		File file = writeReport(sessionStartedAt, System.currentTimeMillis(), nativeReport, uiReport);
		synchronized (LOCK) {
			if (file != null) {
				lastFile = file;
			}
		}
		return file;
	}

	public static void recordUi(String message) {
		synchronized (LOCK) {
			if (recording) {
				appendUiReportLocked(message);
			}
		}
	}

	private static void appendUiReportLocked(String message) {
		if (uiReportTruncated) {
			return;
		}
		long elapsed = startedElapsedRealtime > 0L
				? Math.max(0L, SystemClock.elapsedRealtime() - startedElapsedRealtime) : 0L;
		String line = "[+" + elapsed + "ms] " + safeLine(message) + "\n";
		if (uiReport.length() + line.length() > UI_REPORT_LIMIT) {
			uiReport.append("[+").append(elapsed).append("ms] truncated=true\n");
			uiReportTruncated = true;
		} else {
			uiReport.append(line);
		}
	}

	public static File getLastFile() {
		synchronized (LOCK) {
			return getLastFileLocked();
		}
	}

	public static boolean deleteLastFile() {
		synchronized (LOCK) {
			File file = getLastFileLocked();
			if (file == null) {
				return true;
			}
			boolean deleted = file.delete();
			if (deleted) {
				lastFile = null;
			}
			return deleted;
		}
	}

	private static File getLastFileLocked() {
		if (lastFile != null && lastFile.isFile()) {
			return lastFile;
		}
		File directory = getDirectory();
		File[] files = directory != null ? directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(".txt")) : null;
		if (files == null || files.length == 0) {
			return null;
		}
		Arrays.sort(files, (first, second) ->
				Long.compare(second.lastModified(), first.lastModified()));
		lastFile = files[0];
		return lastFile;
	}

	private static File writeReport(long startedAt, long stoppedAt, String nativeReport, String uiReport) {
		File directory = getDirectory();
		if (directory == null || !(directory.isDirectory() || directory.mkdirs())) {
			return null;
		}
		File file = new File(directory, FILE_PREFIX + formatTime(startedAt, "yyyyMMdd-HHmmss-SSS") + ".txt");
		try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				new FileOutputStream(file), StandardCharsets.UTF_8))) {
			writer.write("Dashchan video diagnostics\n");
			writer.write("schema=2\n");
			writer.write("started_utc=" + formatTime(startedAt, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") + "\n");
			writer.write("stopped_utc=" + formatTime(stoppedAt, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") + "\n");
			writer.write("duration_ms=" + Math.max(0L, stoppedAt - startedAt) + "\n");
			writer.write("app_version=" + safe(BuildConfig.VERSION_NAME) + "\n");
			writer.write("app_version_code=" + BuildConfig.VERSION_CODE + "\n");
			writer.write("player_build=" + safe(BuildConfig.NATIVE_PLAYER_FFMPEG_FLAVOR) + "\n");
			writer.write("player_abis=" + join(BuildConfig.NATIVE_PLAYER_ABIS) + "\n");
			writer.write("android_api=" + Build.VERSION.SDK_INT + "\n");
			writer.write("android_release=" + safe(Build.VERSION.RELEASE) + "\n");
			writer.write("manufacturer=" + safe(Build.MANUFACTURER) + "\n");
			writer.write("model=" + safe(Build.MODEL) + "\n");
			writer.write("device_abis=" + join(Build.SUPPORTED_ABIS) + "\n");
			writer.write("available_video_decoders=" + getAvailableVideoDecoders() + "\n");
			writer.write("privacy=no_urls,no_post_content,no_media_bytes,no_file_names,no_accounts,no_device_ids\n");
			writer.write("\n[native_player]\n");
			if (nativeReport != null && !nativeReport.isEmpty()) {
				writer.write(nativeReport);
				if (nativeReport.charAt(nativeReport.length() - 1) != '\n') {
					writer.write('\n');
				}
			} else {
				writer.write("native_player_not_loaded=true\n");
			}
			writer.write("\n[ui_player]\n");
			if (uiReport != null && !uiReport.isEmpty()) {
				writer.write(uiReport);
				if (uiReport.charAt(uiReport.length() - 1) != '\n') {
					writer.write('\n');
				}
			} else {
				writer.write("ui_player_not_recorded=true\n");
			}
		} catch (IOException e) {
			file.delete();
			return null;
		}
		cleanupOldFiles(directory);
		return file;
	}

	private static File getDirectory() {
		File cacheDirectory = MainApplication.getInstance().getCacheDir();
		return cacheDirectory != null ? new File(cacheDirectory, DIRECTORY_NAME) : null;
	}

	private static void cleanupOldFiles(File directory) {
		File[] files = directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(".txt"));
		if (files == null || files.length <= MAX_FILES) {
			return;
		}
		Arrays.sort(files, (first, second) ->
				Long.compare(second.lastModified(), first.lastModified()));
		for (int i = MAX_FILES; i < files.length; i++) {
			files[i].delete();
		}
	}

	private static String getAvailableVideoDecoders() {
		StringBuilder builder = new StringBuilder();
		try {
			for (MediaCodecInfo codecInfo : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
				if (codecInfo.isEncoder()) {
					continue;
				}
				boolean supported = false;
				for (String type : codecInfo.getSupportedTypes()) {
					if ("video/avc".equalsIgnoreCase(type) || "video/hevc".equalsIgnoreCase(type)) {
						supported = true;
						break;
					}
				}
				if (supported) {
					if (builder.length() > 0) {
						builder.append(',');
					}
					builder.append(safe(codecInfo.getName()));
					builder.append(codecInfo.isHardwareAccelerated() ? "[hw]" : "[sw]");
				}
			}
		} catch (RuntimeException ignored) {
			return "unavailable";
		}
		return builder.length() > 0 ? builder.toString() : "none";
	}

	private static String formatTime(long time, String pattern) {
		SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		return dateFormat.format(new Date(time));
	}

	private static String join(String[] values) {
		StringBuilder builder = new StringBuilder();
		for (String value : values) {
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(safe(value));
		}
		return builder.toString();
	}

	private static String safe(String value) {
		return value != null ? value.replace('\n', ' ').replace('\r', ' ').replace(',', '_') : "";
	}

	private static String safeLine(String value) {
		return value != null ? value.replace('\n', ' ').replace('\r', ' ') : "";
	}
}
