package com.mishiranu.dashchan.util;

import android.net.Uri;
import android.os.SystemClock;
import chan.content.Chan;
import chan.content.ChanManager;
import com.mishiranu.dashchan.content.NetworkObserver;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in network events. Never record URLs, headers, session data, or response contents. */
public final class NetworkDiagnostics {
	private static final AtomicLong NEXT_REQUEST = new AtomicLong();
	private final long capture;
	private final long requestId = NEXT_REQUEST.incrementAndGet();
	private final long started = SystemClock.elapsedRealtime();

	private NetworkDiagnostics(long capture) {
		this.capture = capture;
	}

	public static NetworkDiagnostics start(Chan chan, Uri uri) {
		if (!PostingFormDiagnostics.isRecording()) return null;
		String source = chan.name == null ? "app"
				: ChanManager.getInstance().isBuiltInChan(chan.name) ? chan.name : "extension";
		return start(source, route(uri));
	}

	/** The source label is a fixed name for an app component, never an address. */
	public static NetworkDiagnostics startApp(String component) {
		return PostingFormDiagnostics.isRecording() ? start(component, "app") : null;
	}

	private static NetworkDiagnostics start(String source, String route) {
		NetworkDiagnostics trace = new NetworkDiagnostics(PostingFormDiagnostics.captureId());
		NetworkObserver.Snapshot network = NetworkObserver.getInstance().getSnapshot();
		trace.event("request_start source=" + source + " route=" + route + " available=" + network.available
				+ " wifi=" + network.wifi + " cellular=" + network.cellular + " vpn=" + network.vpn
				+ " validated=" + network.validated);
		return trace;
	}

	private static String route(Uri uri) {
		if ("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) return "websocket";
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
		return endpoint.endsWith(".json") ? "json" : "other";
	}

	public static String contentType(String value) {
		if (value == null) return "unknown";
		int separator = value.indexOf(';');
		String type = (separator >= 0 ? value.substring(0, separator) : value).trim().toLowerCase(Locale.ROOT);
		if ("text/html".equals(type)) return "html";
		if ("application/json".equals(type) || type.endsWith("+json")) return "json";
		if (type.startsWith("image/")) return "image";
		if (type.startsWith("video/")) return "video";
		if (type.startsWith("audio/")) return "audio";
		if ("text/plain".equals(type)) return "plain";
		return "other";
	}

	/** Call with fixed event names and primitive or enum fields only. */
	public void event(String details) {
		PostingFormDiagnostics.recordNetwork(capture, "request=" + requestId
				+ " elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " event=" + details);
	}

	public void failure(String stage, Throwable error) {
		StringBuilder details = new StringBuilder(stage);
		for (int depth = 0; depth < 5 && error != null; depth++, error = error.getCause()) {
			details.append(depth == 0 ? " error=" : "/").append(error.getClass().getSimpleName());
		}
		event(details.toString());
	}
}
