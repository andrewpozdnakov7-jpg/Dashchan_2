package com.mishiranu.dashchan.chan.soyjak;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.AdvancedPreferences;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.net.UserAgentProvider;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.MessageDialog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded, always-on Soyjak access diagnostics.
 *
 * <p>Answers one question without a computer: whether the native stack can read the site with
 * the clearance that the user has just obtained in the visible browser. Records only fixed
 * event names and primitive values: no cookie values, no page text, no full addresses.</p>
 *
 * <p>The same lines are appended to the shared recording when the user starts
 * {@code Settings -> Diagnostics -> Record app diagnostics}, so an exported report contains
 * the Soyjak section without a second switch.</p>
 */
public final class SoyjakDiagnostics {
	public static final String CHAN_NAME = "soyjak";
	public static final String CLEARANCE_COOKIE = "cf_clearance";

	private static final int MAX_LINES = 800;
	private static final int MAX_CHARS = 128 * 1024;
	private static final String PREFS_NAME = "soyjak.diagnostics";
	private static final String PREF_SAVED_AT = "clearance.savedAt";
	private static final String PREF_FINGERPRINT = "clearance.fingerprint";
	private static final String PREF_LENGTH = "clearance.length";

	private static final Object LOCK = new Object();
	private static final ArrayDeque<String> LINES = new ArrayDeque<>();
	private static final long STARTED = SystemClock.elapsedRealtime();
	private static final AtomicBoolean PROBE_RUNNING = new AtomicBoolean();
	private static int characters;
	private static long dropped;
	private static volatile String lastProbe = "none";

	private SoyjakDiagnostics() {}

	public static boolean isSoyjak(Chan chan) {
		return chan != null && CHAN_NAME.equals(chan.name);
	}

	/** Same route vocabulary as the shared network diagnostics: no addresses, only endpoints. */
	public static String route(Uri uri) {
		if (uri == null) return "none";
		List<String> path = uri.getPathSegments();
		if (path.isEmpty()) return "root";
		String endpoint = path.get(path.size() - 1);
		if ("catalog.json".equals(endpoint) || "catalog.html".equals(endpoint)) return "catalog";
		if (path.size() >= 2) {
			switch (path.get(path.size() - 2)) {
				case "thread": case "res": return "thread";
				case "thumb": return "thumbnail";
				case "src": return "attachment";
			}
		}
		if (endpoint.endsWith(".json")) return "json";
		if (endpoint.endsWith(".html")) return "page";
		return "other";
	}

	public static String fingerprint(String value) {
		if (StringUtils.isEmpty(value)) return "none";
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(8);
			for (int i = 0; i < 4; i++) {
				builder.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			return "error";
		}
	}

	/** Fixed event names and primitive fields only. Never pass cookies, titles or page text. */
	public static void event(String name, String details) {
		String line = "t=+" + (SystemClock.elapsedRealtime() - STARTED) + "ms " + name
				+ (StringUtils.isEmpty(details) ? "" : " " + details);
		synchronized (LOCK) {
			LINES.addLast(line);
			characters += line.length();
			while (LINES.size() > MAX_LINES || characters > MAX_CHARS) {
				String removed = LINES.removeFirst();
				characters -= removed.length();
				dropped++;
			}
		}
		if (PostingFormDiagnostics.isRecording()) {
			PostingFormDiagnostics.recordNetwork(PostingFormDiagnostics.captureId(), "soyjak " + line);
		}
	}

	public static void clear() {
		synchronized (LOCK) {
			LINES.clear();
			characters = 0;
			dropped = 0;
		}
	}

	private static SharedPreferences preferences() {
		return MainApplication.getInstance().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	static void onClearanceStored(String clearance) {
		SharedPreferences.Editor editor = preferences().edit();
		editor.putLong(PREF_SAVED_AT, System.currentTimeMillis());
		editor.putString(PREF_FINGERPRINT, fingerprint(clearance));
		editor.putInt(PREF_LENGTH, clearance.length());
		editor.apply();
	}

	private static String storedClearance(Chan chan) {
		try {
			return chan != null && chan.configuration != null
					? chan.configuration.getCookie(CLEARANCE_COOKIE) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static String cookieReport() {
		Chan chan = Chan.get(CHAN_NAME);
		String clearance = storedClearance(chan);
		if (StringUtils.isEmpty(clearance)) {
			return "cookie=none";
		}
		SharedPreferences preferences = preferences();
		long savedAt = preferences.getLong(PREF_SAVED_AT, 0L);
		String saved = savedAt > 0 ? (System.currentTimeMillis() - savedAt) + "ms" : "unknown";
		return "cookie=present len=" + clearance.length()
				+ " fp=" + fingerprint(clearance)
				+ " savedAgeMs=" + saved
				+ " key=" + CLEARANCE_COOKIE
				+ " matchesLastStore=" + fingerprint(clearance).equals(preferences.getString(PREF_FINGERPRINT, ""));
	}

	public static String userAgentReport() {
		String nativeUserAgent = AdvancedPreferences.getUserAgent(CHAN_NAME);
		String webViewUserAgent = UserAgentProvider.getInstance().getUserAgent();
		return "uaSame=" + (nativeUserAgent != null && nativeUserAgent.equals(webViewUserAgent))
				+ " uaNativeFp=" + fingerprint(nativeUserAgent)
				+ " uaWebViewFp=" + fingerprint(webViewUserAgent)
				+ "\nuaNative=" + nativeUserAgent
				+ "\nuaWebView=" + webViewUserAgent;
	}

	public static String lastProbe() {
		return lastProbe;
	}

	public static String snapshot() {
		Chan chan = Chan.get(CHAN_NAME);
		StringBuilder builder = new StringBuilder();
		builder.append("Slooop Soyjak diagnostics\n")
				.append("schema=2\n")
				.append("app=").append(BuildConfig.VERSION_NAME).append(" code=").append(BuildConfig.VERSION_CODE).append('\n')
				.append("reportUtcMillis=").append(System.currentTimeMillis()).append('\n')
				.append("uptimeMs=").append(SystemClock.elapsedRealtime() - STARTED).append('\n')
				.append("chanInstalled=").append(chan != null && CHAN_NAME.equals(chan.name)).append('\n')
				.append(cookieReport()).append('\n')
				.append(userAgentReport()).append('\n')
				.append("headlessEnabled=").append(SoyjakHeadlessChallenge.ENABLED)
				.append(" headlessSuppressed=").append(SoyjakHeadlessChallenge.isSuppressed()).append('\n')
				.append("lastProbe=").append(lastProbe).append('\n');
		synchronized (LOCK) {
			builder.append("dropped=").append(dropped).append(" retained=").append(LINES.size()).append('\n');
			builder.append("--- events ---\n");
			for (String line : LINES) {
				builder.append(line).append('\n');
			}
		}
		return builder.toString();
	}

	/** Native probe of the catalog endpoint with whatever the app currently sends. */
	public static void probe(Chan chan, Consumer<String> callback) {
		if (!PROBE_RUNNING.compareAndSet(false, true)) {
			ConcurrentUtils.HANDLER.post(() -> callback.accept("probe=busy lastProbe=" + lastProbe));
			return;
		}
		ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
			String result;
			try {
				result = runProbe(chan);
			} catch (RuntimeException e) {
				result = "error=" + e.getClass().getSimpleName();
			} finally {
				PROBE_RUNNING.set(false);
			}
			lastProbe = result;
			event("probe_result", result);
			String finalResult = result;
			ConcurrentUtils.HANDLER.post(() -> callback.accept(finalResult));
		});
	}

	private static String runProbe(Chan chan) {
		if (chan == null || !CHAN_NAME.equals(chan.name) || !(chan.locator instanceof SoyjakChanLocator locator)) {
			return "error=no_chan";
		}
		event("probe_start", cookieReport());
		long started = SystemClock.elapsedRealtime();
		Uri uri = locator.createThreadsApiUri("soy", ChanPerformer.ReadThreadsData.PAGE_NUMBER_CATALOG);
		HttpHolder holder = new HttpHolder(chan);
		try (HttpHolder.Use use = holder.use()) {
			// HttpHolder rejects work from a thread that never acquired "use".
			holder.setMayResolveFirewallBlock(false);
			HttpResponse response = new HttpRequest(uri, holder).perform();
			int code = response.getResponseCode();
			boolean challenge = hasHeader(response.getHeaderFields(), "CF-Mitigated");
			String type = header(response.getHeaderFields(), "Content-Type");
			int bytes = 0;
			String problem = "";
			try {
				bytes = response.readString().length();
			} catch (HttpException e) {
				problem = " body=" + errorName(e);
			}
			return "status=" + code
					+ " cfMitigated=" + challenge
					+ " type=" + contentType(type)
					+ " bytes=" + bytes
					+ " durMs=" + (SystemClock.elapsedRealtime() - started)
					+ problem;
		} catch (HttpException e) {
			return "status=failed error=" + errorName(e)
					+ " durMs=" + (SystemClock.elapsedRealtime() - started);
		}
	}

	private static String errorName(HttpException exception) {
		ErrorItem item = exception.getErrorItemAndHandle();
		return item != null && item.type != null ? item.type.name() : "UNKNOWN";
	}

	private static boolean hasHeader(Map<String, List<String>> fields, String name) {
		for (String key : fields.keySet()) {
			if (key != null && key.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	private static String header(Map<String, List<String>> fields, String name) {
		for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
			if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
				List<String> values = entry.getValue();
				return values != null && !values.isEmpty() ? values.get(0) : null;
			}
		}
		return null;
	}

	private static String contentType(String value) {
		if (value == null) return "unknown";
		int separator = value.indexOf(';');
		String type = (separator >= 0 ? value.substring(0, separator) : value).trim().toLowerCase(Locale.ROOT);
		if ("text/html".equals(type)) return "html";
		if ("application/json".equals(type) || type.endsWith("+json")) return "json";
		if (type.startsWith("image/")) return "image";
		return "other";
	}

	/** Screen entry point: full report with copy and an immediate native probe. */
	public static void showReport(Context context) {
		if (!isUsable(context)) return;
		String report = snapshot();
		new MessageDialog.Builder(context)
				.setTitle(R.string.soyjak_diagnostics_title)
				.setMessage(report)
				.setPositiveButton(R.string.copy, (dialog, which) -> StringUtils.copyToClipboard(context, report))
				.setNeutralButton(R.string.soyjak_probe, (dialog, which) -> probeAndShow(context))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Screen entry point: run the probe and reopen the report with its result. */
	public static void probeAndShow(Context context) {
		if (!isUsable(context)) return;
		Chan chan = Chan.get(CHAN_NAME);
		if (chan == null) {
			new MessageDialog.Builder(context)
					.setTitle(R.string.soyjak_diagnostics_title)
					.setMessage(R.string.soyjak_unavailable)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}
		probe(chan, result -> showReport(context));
	}

	private static boolean isUsable(Context context) {
		if (context == null) return true;
		if (context instanceof Activity activity) {
			return !activity.isFinishing() && !activity.isDestroyed();
		}
		return true;
	}
}
