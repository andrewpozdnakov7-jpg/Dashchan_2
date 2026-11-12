package com.mishiranu.dashchan.media.compression;

import chan.content.Chan;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import com.mishiranu.dashchan.chan.dvach.DvachChanConfiguration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Read-only equivalent of the board's form limits. No passcode is guessed or logged. */
public final class UploadLimitResolver {
	private static final String SCRIPTS_REGEX = "<script(?:\\s[^>]*)?>(.*?)</script\\s*>";
	// Android's ICU requires a literal closing brace to be escaped outside a character class.
	private static final String BOARD_REGEX = "_CFG\\.BOARD\\s*=\\s*\\{([^}]{0,16384})\\}";
	private static final String BOARD_KIB_REGEX = "MAXFILESSIZE\\s*:\\s*(?:parseInt\\(\\s*[\"'](\\d{1,12})[\"']\\s*\\)|([0-9]{1,12}))\\s*(?:,|$)";
	private UploadLimitResolver() {}
	public static long parseBoardBytes(String html) {
		// Keep parser failures inside the caller's normal Exception boundary, not <clinit>.
		// This runs once per limit lookup, not per frame or progress update.
		Pattern scriptsPattern = Pattern.compile(SCRIPTS_REGEX, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		Pattern boardPattern = Pattern.compile(BOARD_REGEX, Pattern.DOTALL);
		Pattern limitPattern = Pattern.compile(BOARD_KIB_REGEX);
		Matcher scripts = scriptsPattern.matcher(html);
		while (scripts.find()) {
			Matcher board = boardPattern.matcher(scripts.group(1));
			if (board.find()) {
				Matcher limit = limitPattern.matcher(board.group(1));
				try { return limit.find() ? Math.multiplyExact(Long.parseLong(limit.group(1) != null ? limit.group(1) : limit.group(2)), 1024) : 0; }
				catch (ArithmeticException | NumberFormatException e) { return 0; }
			}
		}
		return 0;
	}
	public static long resolve(Chan chan, String board) throws Exception {
		if (!(chan.configuration instanceof DvachChanConfiguration) || board == null || !board.matches("[a-zA-Z0-9_]+")) return 0;
		HttpHolder holder = new HttpHolder(chan);
		try (HttpHolder.Use use = holder.use()) {
			String cookie = chan.configuration.getCookie("passcode_auth");
			String html = new HttpRequest(chan.locator.buildPath(board, ""), holder).setTimeouts(10_000, 15_000)
					.addCookie("passcode_auth", cookie).perform().readString();
			long base = parseBoardBytes(html);
			if (cookie != null && !cookie.isEmpty()) {
				// The browser applies max(board limit, authenticated API limit), with KiB units.
				JSONObject data = new JSONObject(new HttpRequest(chan.locator.buildPath("api", "captcha", "emoji", "id"), holder)
						.setTimeouts(10_000, 15_000).addCookie("passcode_auth", cookie).perform().readString());
				if (data.optInt("result") == 2) {
					long kib = data.optLong("maxFilesSize");
					if (kib > 0 && kib <= Long.MAX_VALUE / 1024) base = Math.max(base, kib * 1024);
				}
			}
			return base;
		}
	}
}
