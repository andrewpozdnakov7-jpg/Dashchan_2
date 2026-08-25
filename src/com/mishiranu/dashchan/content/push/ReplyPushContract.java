package com.mishiranu.dashchan.content.push;

import chan.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ReplyPushContract {
	public static final String SUPPORTED_CHAN_NAME = "dvach";
	public static final String SUPPORTED_BOARD_NAME = "mobi";
	public static final long MAX_SAFE_INTEGER = 9007199254740991L;
	public static final long IDENTITY_RESET_COOLDOWN_MILLIS = 6L * 60L * 60L * 1000L;

	private static final Pattern BOARD_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
	private static final Pattern UUID_V4_PATTERN = Pattern.compile(
			"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

	private ReplyPushContract() {}

	public static String newInstallationId() {
		return UUID.randomUUID().toString().toLowerCase(Locale.US);
	}

	public static boolean isInstallationId(String value) {
		return value != null && UUID_V4_PATTERN.matcher(value).matches();
	}

	public static boolean isBoardName(String value) {
		return value != null && BOARD_PATTERN.matcher(value).matches();
	}

	public static boolean isCanonicalPositiveNumber(String value) {
		if (StringUtils.isEmpty(value) || value.length() > 16 || value.charAt(0) < '1'
				|| value.charAt(0) > '9') {
			return false;
		}
		long number = 0L;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
			number = number * 10L + c - '0';
			if (number > MAX_SAFE_INTEGER) {
				return false;
			}
		}
		return true;
	}

	public static String makeWatchId(String installationId, String chanName, String boardName,
			String threadNumber, String postNumber) {
		if (!isInstallationId(installationId) || !SUPPORTED_CHAN_NAME.equals(chanName)
				|| !SUPPORTED_BOARD_NAME.equals(boardName) || !isCanonicalPositiveNumber(threadNumber)
				|| !isCanonicalPositiveNumber(postNumber)) {
			return null;
		}
		return sha256(installationId + '\n' + chanName + '\n' + boardName + '\n'
				+ threadNumber + '\n' + postNumber);
	}

	public static boolean isMinuteInQuietHours(int currentMinute, int startMinute, int endMinute) {
		if (currentMinute < 0 || currentMinute >= 24 * 60 || startMinute < 0 || startMinute >= 24 * 60
				|| endMinute < 0 || endMinute >= 24 * 60 || startMinute == endMinute) {
			return false;
		}
		return startMinute < endMinute
				? currentMinute >= startMinute && currentMinute < endMinute
				: currentMinute >= startMinute || currentMinute < endMinute;
	}

	public static long getCooldownRemaining(long now, long lastSuccess, long cooldown) {
		if (lastSuccess <= 0L || cooldown <= 0L) {
			return 0L;
		}
		long elapsed = now - lastSuccess;
		if (elapsed < 0L) {
			return cooldown;
		}
		return elapsed < cooldown ? cooldown - elapsed : 0L;
	}

	public static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				builder.append(String.format(Locale.US, "%02x", b & 0xff));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
