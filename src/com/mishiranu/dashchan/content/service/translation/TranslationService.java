package com.mishiranu.dashchan.content.service.translation;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;
import com.mishiranu.dashchan.content.translation.GeminiNanoTranslationBridge;
import com.mishiranu.dashchan.content.translation.GoogleTranslationBridge;
import com.mishiranu.dashchan.content.translation.TranslationEngine;
import com.mishiranu.dashchan.content.translation.TranslationModel;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public class TranslationService extends Service {
	private static final String HOST = "translation.slooop.local";
	private static final String BASE_URL = "https://" + HOST + "/";
	private static final long INITIALIZATION_TIMEOUT_MS = 90000L;
	private static final long IDLE_SHUTDOWN_DELAY_MS = 30000L;

	private static final class Request {
		public final long id;
		public final String subject;
		public final String html;
		public final ITranslationCallback callback;

		private Request(long id, String subject, String html, ITranslationCallback callback) {
			this.id = id;
			this.subject = subject;
			this.html = html;
			this.callback = callback;
		}
	}

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Map<Long, Request> requests = new LinkedHashMap<>();
	private WebView webView;
	private final GeminiNanoTranslationBridge geminiNanoBridge = new GeminiNanoTranslationBridge();
	private final GoogleTranslationBridge googleBridge = new GoogleTranslationBridge();
	private TranslationEngine engine;
	private TranslationModel.Direction direction;
	private boolean ready;
	private boolean initializationStarted;
	private boolean bound;
	private int generation;
	private final Runnable idleShutdownRunnable = () -> {
		if (!bound && requests.isEmpty()) {
			resetEngine("Translator disconnected");
			stopSelf();
		}
	};

	private final ITranslationService.Stub binder = new ITranslationService.Stub() {
		@Override
		public void translate(long requestId, String engine, String sourceLanguage, String targetLanguage,
				String subject, String html,
				ITranslationCallback callback) {
			if (callback == null) {
				return;
			}
			handler.post(() -> enqueue(requestId, engine, sourceLanguage, targetLanguage, subject, html, callback));
		}

		@Override
		public void unload() {
			handler.post(() -> resetEngine("Translator unloaded"));
		}
	};

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		bound = true;
		handler.removeCallbacks(idleShutdownRunnable);
		return binder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		bound = false;
		scheduleIdleShutdown();
		return false;
	}

	@Override
	public void onDestroy() {
		handler.removeCallbacks(idleShutdownRunnable);
		resetEngine("Translator stopped");
		super.onDestroy();
	}

	private void enqueue(long requestId, String engineValue, String sourceLanguage, String targetLanguage,
			String subject, String html,
			ITranslationCallback callback) {
		TranslationEngine requestedEngine = TranslationEngine.fromValue(engineValue);
		TranslationModel.Direction requestedDirection = obtainDirection(sourceLanguage, targetLanguage);
		if (!requestedEngine.isAvailable() || requestedDirection == null || subject == null || html == null) {
			reportError(callback, requestId, "Unsupported translation direction");
			return;
		}
		if (requestedEngine == TranslationEngine.MOZILLA && !TranslationModel.isInstalled(this, requestedDirection)) {
			reportError(callback, requestId, "Language package is not installed");
			return;
		}
		if (engine != requestedEngine || direction != requestedDirection) {
			resetEngine("Translation direction changed");
			engine = requestedEngine;
			direction = requestedDirection;
		}
		requests.put(requestId, new Request(requestId, subject, html, callback));
		if (engine == TranslationEngine.GOOGLE) {
			final int currentGeneration = generation;
			googleBridge.translate(this, direction, subject, html, new GoogleTranslationBridge.Callback() {
				@Override
				public void onSuccess(String translatedSubject, String translatedHtml) {
					handler.post(() -> onResult(currentGeneration, Long.toString(requestId), translatedSubject,
							translatedHtml, null));
				}

				@Override
				public void onError(String message) {
					handler.post(() -> onResult(currentGeneration, Long.toString(requestId), null, null,
							message != null ? message : "Translation failed"));
				}
			});
		} else if (engine == TranslationEngine.GEMINI_NANO) {
			final int currentGeneration = generation;
			geminiNanoBridge.translate(this, direction, subject, html, new GeminiNanoTranslationBridge.Callback() {
				@Override
				public void onSuccess(String translatedSubject, String translatedHtml) {
					handler.post(() -> onResult(currentGeneration, Long.toString(requestId), translatedSubject,
							translatedHtml, null));
				}

				@Override
				public void onError(String message) {
					handler.post(() -> onResult(currentGeneration, Long.toString(requestId), null, null,
							message != null ? message : "Translation failed"));
				}
			});
		} else if (webView == null) {
			createEngine();
		} else if (ready) {
			sendRequest(requests.get(requestId));
		}
	}

	private static TranslationModel.Direction obtainDirection(String sourceLanguage, String targetLanguage) {
		for (TranslationModel.Direction direction : TranslationModel.Direction.values()) {
			if (direction.sourceLanguage.equals(sourceLanguage) && direction.targetLanguage.equals(targetLanguage)) {
				return direction;
			}
		}
		return null;
	}

	@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
	private void createEngine() {
		final int currentGeneration = ++generation;
		ready = false;
		initializationStarted = false;
		webView = new WebView(this);
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setAllowFileAccess(false);
		settings.setAllowContentAccess(false);
		settings.setDomStorageEnabled(false);
		settings.setDatabaseEnabled(false);
		settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		webView.addJavascriptInterface(new JavascriptBridge(currentGeneration), "SlooopTranslation");
		webView.setWebViewClient(new LocalClient(currentGeneration, direction));
		webView.loadUrl(BASE_URL + "runtime/runner.html");
		handler.postDelayed(() -> {
			if (currentGeneration == generation && !ready) {
				resetEngine("Translator initialization timed out");
			}
		}, INITIALIZATION_TIMEOUT_MS);
	}

	private void initializeEngine(int currentGeneration) {
		if (webView == null || currentGeneration != generation || initializationStarted || direction == null) {
			return;
		}
		initializationStarted = true;
		String script = "window.SlooopBergamot.initialize(" + JSONObject.quote(direction.sourceLanguage) + "," +
				JSONObject.quote(direction.targetLanguage) + ");";
		webView.evaluateJavascript(script, null);
	}

	private void sendRequest(Request request) {
		if (request == null || webView == null || !ready) {
			return;
		}
		String script = "window.SlooopBergamot.translate(" + request.id + "," +
				JSONObject.quote(request.subject) + "," + JSONObject.quote(request.html) + ");";
		webView.evaluateJavascript(script, null);
	}

	private void onReady(int currentGeneration) {
		if (currentGeneration != generation || webView == null) {
			return;
		}
		ready = true;
		for (Request request : new ArrayList<>(requests.values())) {
			sendRequest(request);
		}
	}

	private void onResult(int currentGeneration, String requestId, String translatedSubject,
			String translatedHtml, String error) {
		if (currentGeneration != generation) {
			return;
		}
		long id;
		try {
			id = Long.parseLong(requestId);
		} catch (NumberFormatException e) {
			return;
		}
		Request request = requests.remove(id);
		if (request == null) {
			return;
		}
		try {
			if (error == null) {
				request.callback.onSuccess(id, translatedSubject, translatedHtml);
			} else {
				request.callback.onError(id, error);
			}
		} catch (RemoteException ignored) {}
		if (requests.isEmpty()) {
			scheduleIdleShutdown();
		}
	}

	private void resetEngine(String message) {
		handler.removeCallbacks(idleShutdownRunnable);
		generation++;
		ready = false;
		initializationStarted = false;
		direction = null;
		engine = null;
		geminiNanoBridge.unload();
		googleBridge.unload();
		if (webView != null) {
			webView.stopLoading();
			webView.removeJavascriptInterface("SlooopTranslation");
			webView.destroy();
			webView = null;
		}
		for (Request request : new ArrayList<>(requests.values())) {
			reportError(request.callback, request.id, message);
		}
		requests.clear();
	}

	private static void reportError(ITranslationCallback callback, long requestId, String message) {
		try {
			callback.onError(requestId, message);
		} catch (RemoteException ignored) {}
	}

	private void scheduleIdleShutdown() {
		handler.removeCallbacks(idleShutdownRunnable);
		if (!bound && requests.isEmpty()) {
			handler.postDelayed(idleShutdownRunnable, IDLE_SHUTDOWN_DELAY_MS);
		}
	}

	private final class JavascriptBridge {
		private final int bridgeGeneration;

		private JavascriptBridge(int bridgeGeneration) {
			this.bridgeGeneration = bridgeGeneration;
		}

		@JavascriptInterface
		public void onReady() {
			handler.post(() -> TranslationService.this.onReady(bridgeGeneration));
		}

		@JavascriptInterface
		public void onInitializationError(String message) {
			handler.post(() -> {
				if (bridgeGeneration == generation) {
					resetEngine(message != null ? message : "Translator initialization failed");
				}
			});
		}

		@JavascriptInterface
		public void onTranslationResult(String requestId, String translatedSubject, String translatedHtml) {
			handler.post(() -> onResult(bridgeGeneration, requestId, translatedSubject, translatedHtml, null));
		}

		@JavascriptInterface
		public void onTranslationError(String requestId, String message) {
			handler.post(() -> onResult(bridgeGeneration, requestId, null, null,
					message != null ? message : "Translation failed"));
		}
	}

	private final class LocalClient extends WebViewClient {
		private final int clientGeneration;
		private final TranslationModel.Direction clientDirection;

		private LocalClient(int clientGeneration, TranslationModel.Direction clientDirection) {
			this.clientGeneration = clientGeneration;
			this.clientDirection = clientDirection;
		}

		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return !isAllowed(request.getUrl());
		}

		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
			return openLocalResource(request.getUrl(), clientDirection);
		}

		@Override
		@SuppressWarnings("deprecation")
		public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
			return openLocalResource(Uri.parse(url), clientDirection);
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			if ((BASE_URL + "runtime/runner.html").equals(url)) {
				initializeEngine(clientGeneration);
			}
		}

		@Override
		public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
			handler.post(() -> {
				if (clientGeneration == generation) {
					resetEngine(detail.didCrash() ? "Translator process crashed" : "Translator process stopped");
				}
			});
			return true;
		}
	}

	private static boolean isAllowed(Uri uri) {
		return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost());
	}

	private WebResourceResponse openLocalResource(Uri uri, TranslationModel.Direction resourceDirection) {
		if (!isAllowed(uri)) {
			return notFoundResponse();
		}
		String path = uri.getPath();
		try {
			if ("/runtime/runner.html".equals(path)) {
				return response("text/html", getAssets().open("translation/runner.html"));
			} else if ("/runtime/runner.js".equals(path)) {
				return response("application/javascript", getAssets().open("translation/runner.js"));
			} else if ("/runtime/bergamot-translator.js".equals(path)) {
				return response("application/javascript",
						getAssets().open("translation/bergamot-translator.js"));
			} else if ("/runtime/bergamot-translator.wasm".equals(path)) {
				return response("application/wasm", getAssets().open("translation/bergamot-translator.wasm"));
			} else if (path != null && path.startsWith("/model/") && resourceDirection != null) {
				String name = path.substring("/model/".length());
				if ("model.bin".equals(name) || "lex.bin".equals(name) || "vocab.spm".equals(name)) {
					File directory = TranslationModel.getModelDirectory(TranslationService.this, resourceDirection);
					return response("application/octet-stream", new FileInputStream(new File(directory, name)));
				}
			}
		} catch (IOException ignored) {}
		return notFoundResponse();
	}

	private static WebResourceResponse response(String mimeType, InputStream input) {
		return new WebResourceResponse(mimeType, null, 200, "OK",
				Collections.singletonMap("Cache-Control", "no-store"), input);
	}

	private static WebResourceResponse notFoundResponse() {
		return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
				Collections.singletonMap("Cache-Control", "no-store"),
				new ByteArrayInputStream("Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
