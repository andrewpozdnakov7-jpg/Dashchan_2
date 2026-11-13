package com.mishiranu.dashchan.media.compression;

import com.mishiranu.dashchan.content.Preferences;
import java.math.BigDecimal;

/** Separate from decoder/player preferences. Empty limits deliberately have no invented default. */
public final class CompressionSettings {
	public static final String ENABLED = "experimental_video_compression";
	public static final String ENGINE = "video_compression_engine";
	public static final String CODEC = "video_compression_codec";
	public static final String ALWAYS = "video_compression_always";
	public static final String FILE_LIMIT = "video_compression_file_mb";
	public static final String TOTAL_LIMIT = "video_compression_total_mb";
	private CompressionSettings() {}
	public static boolean enabled() { return com.mishiranu.dashchan.BuildConfig.ENABLE_VIDEO_COMPRESSION && Preferences.PREFERENCES.getBoolean(ENABLED, false); }
	public static String codec() { return Preferences.PREFERENCES.getString(CODEC, "auto"); }
	public static boolean always() { return Preferences.PREFERENCES.getBoolean(ALWAYS, false); }
	public static long fileLimit() { return bytes(Preferences.PREFERENCES.getString(FILE_LIMIT, "")); }
	public static long totalLimit() { return bytes(Preferences.PREFERENCES.getString(TOTAL_LIMIT, "")); }
	public static boolean configured() { return fileLimit() > 0 && totalLimit() > 0; }
	public static long bytes(String mb) {
		try { return new BigDecimal(mb.trim().replace(',', '.')).multiply(new BigDecimal(1_000_000))
				.longValueExact(); }
		catch (ArithmeticException | NumberFormatException | NullPointerException e) { return 0; }
	}
	public static String signature() { return enabled() + ":" + codec() + ":" + fileLimit() + ":" + totalLimit() + ":" + always(); }
}
