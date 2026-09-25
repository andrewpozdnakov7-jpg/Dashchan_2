package com.mishiranu.dashchan.content.translation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import chan.content.Chan;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.service.translation.ITranslationCallback;
import com.mishiranu.dashchan.content.service.translation.ITranslationService;
import com.mishiranu.dashchan.content.service.translation.TranslationService;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class TranslationController {
	private static final long REQUEST_TIMEOUT_MS = 180000L;
	private static final long IDLE_DISCONNECT_DELAY_MS = 120000L;

	public interface ResultCallback {
		void onResult(String translatedSubject, String translatedHtml, String error);
	}

	private static final class PendingCall {
		public final long id;
		public final TranslationEngine engine;
		public final TranslationModel.Direction direction;
		public final String subject;
		public final String html;
		public final ResultCallback callback;

		private PendingCall(long id, TranslationEngine engine, TranslationModel.Direction direction,
				String subject, String html,
				ResultCallback callback) {
			this.id = id;
			this.engine = engine;
			this.direction = direction;
			this.subject = subject;
			this.html = html;
			this.callback = callback;
		}
	}

	private static final TranslationController INSTANCE = new TranslationController();

	public static TranslationController getInstance() {
		return INSTANCE;
	}

	public static TranslationModel.Direction getCurrentDirection() {
		return TranslationModel.forNativeLanguage(Preferences.getTranslationNativeLanguage());
	}

	public static TranslationEngine getCurrentEngine() {
		return Preferences.getTranslationEngine();
	}

	public static String getCurrentCacheKey() {
		return getCurrentEngine().getCacheKey(getCurrentDirection());
	}

	public static boolean isEnabledForChan(String chanName) {
		return BuildConfig.ENABLE_LOCAL_TRANSLATION && Preferences.isLocalTranslationEnabled() &&
				TranslationModel.isForeignChan(getCurrentDirection(), chanName);
	}

	public static boolean isEnabledForDirection(TranslationModel.Direction direction) {
		return BuildConfig.ENABLE_LOCAL_TRANSLATION && Preferences.isLocalTranslationEnabled() &&
				getCurrentDirection() == direction;
	}

	public static boolean isReadyForChan(String chanName) {
		TranslationModel.Direction direction = getCurrentDirection();
		if (!isEnabledForChan(chanName)) {
			return false;
		}
		return isReadyForDirection(direction);
	}

	public static boolean isReadyForDirection(TranslationModel.Direction direction) {
		if (!isEnabledForDirection(direction)) {
			return false;
		}
		return getEngineState(getCurrentEngine(), direction) == TranslationModelManager.State.INSTALLED;
	}

	private static TranslationModelManager.State getEngineState(TranslationEngine engine,
			TranslationModel.Direction direction) {
		switch (engine) {
			case GOOGLE: {
				return GoogleTranslationBridge.getSnapshot(direction).state;
			}
			case GEMINI_NANO: {
				return GeminiNanoTranslationBridge.getSnapshot(direction).state;
			}
			default: {
				return TranslationModelManager.getInstance().getSnapshot(direction).state;
			}
		}
	}

	private final MainApplication application = MainApplication.getInstance();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AtomicLong nextRequestId = new AtomicLong(1L);
	private final Map<Long, PendingCall> calls = new HashMap<>();
	private final Map<PostItem, String> pendingPosts = new IdentityHashMap<>();
	private final ExecutorService cacheExecutor = ConcurrentUtils.newSingleThreadPool(3000, "TranslationCache", null);
	private final TranslationCache persistentCache = new TranslationCache();
	private final AtomicLong cacheGeneration = new AtomicLong();
	private final Map<String, ArrayList<ResultCallback>> sharedTranslations = new HashMap<>();
	private ITranslationService service;
	private boolean binding;
	private final Runnable idleDisconnectRunnable = () -> {
		if (calls.isEmpty()) {
			disconnect();
		}
	};

	private final ITranslationCallback callback = new ITranslationCallback.Stub() {
		@Override
		public void onSuccess(long requestId, String translatedSubject, String translatedHtml) {
			handler.post(() -> finish(requestId, translatedSubject, translatedHtml, null));
		}

		@Override
		public void onError(long requestId, String message) {
			handler.post(() -> finish(requestId, null, null, message));
		}
	};

	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			service = ITranslationService.Stub.asInterface(binder);
			binding = false;
			TranslationDiagnostics.log("controller", "service_connected", "pending", calls.size());
			dispatchPending();
			if (calls.isEmpty()) {
				scheduleIdleDisconnect();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			handler.removeCallbacks(idleDisconnectRunnable);
			service = null;
			binding = false;
			TranslationDiagnostics.error("controller", "service_disconnected", "pending", calls.size());
			failAll("Translation service disconnected");
		}
	};

	private TranslationController() {}

	public void requestPostTranslation(PostItem postItem, Chan chan, Runnable onTranslated) {
		TranslationEngine engine = getCurrentEngine();
		TranslationModel.Direction direction = getCurrentDirection();
		String key = engine.getCacheKey(direction);
		String pendingKey = cacheGeneration.get() + ":" + key;
		if (!isEnabledForChan(chan.name) || postItem.hasTranslatedComment(key)) {
			return;
		}
		synchronized (pendingPosts) {
			if (pendingKey.equals(pendingPosts.get(postItem))) {
				return;
			}
			pendingPosts.put(postItem, pendingKey);
		}
		translateCached("post:" + chan.name, engine, direction, postItem.getSubject(), postItem.getCommentHtmlForTranslation(),
				(translatedSubject, translatedHtml, error) -> {
			synchronized (pendingPosts) {
				if (!pendingKey.equals(pendingPosts.get(postItem))) return;
				pendingPosts.remove(postItem);
			}
			if (translatedHtml != null && key.equals(getCurrentCacheKey()) && isEnabledForChan(chan.name)) {
				postItem.setTranslatedPost(key, translatedSubject, translatedHtml, chan);
				onTranslated.run();
			}
		});
	}

	public void requestTranslation(TranslationModel.Direction direction, String subject, String html,
			ResultCallback resultCallback) {
		if (!isEnabledForDirection(direction)) {
			TranslationDiagnostics.error("controller", "request_rejected", "reason", "engine_not_ready",
					"engine", getCurrentEngine().value, "direction", direction.name());
			resultCallback.onResult(null, null, "Translation package is unavailable");
			return;
		}
		translateCached("reddit-web", getCurrentEngine(), direction, subject, html, resultCallback);
	}

	public void unload() {
		cacheGeneration.incrementAndGet();
		disconnect();
		failAll("Translator unloaded");
	}

	public void clearPersistentCache(Consumer<Boolean> callback) {
		unload();
		cacheExecutor.execute(() -> {
			boolean success = persistentCache.clear();
			if (callback != null) handler.post(() -> callback.accept(success));
		});
	}

	private void translateCached(String scope, TranslationEngine engine, TranslationModel.Direction direction,
			String subject, String html, ResultCallback callback) {
		long generation = cacheGeneration.get();
		cacheExecutor.execute(() -> {
			if (generation != cacheGeneration.get()) {
				handler.post(() -> callback.onResult(null, null, "Translator unloaded"));
				return;
			}
			String cacheKey;
			try {
				cacheKey = persistentCache.key(scope, engine, direction, subject, html);
			} catch (RuntimeException e) {
				TranslationDiagnostics.error("cache", "key_failed", "type", e.getClass().getSimpleName());
				handler.post(() -> {
					if (generation == cacheGeneration.get()) translateWhenReady(engine, direction, subject, html,
							callback, generation, SystemClock.elapsedRealtime() + 15000L, false);
					else callback.onResult(null, null, "Translator unloaded");
				});
				return;
			}
			String key = cacheKey;
			String sharedKey = generation + ":" + key;
			TranslationCache.Result cached = persistentCache.get(key);
			handler.post(() -> {
				if (generation != cacheGeneration.get()) {
					callback.onResult(null, null, "Translator unloaded");
					return;
				}
				if (cached != null) {
					TranslationDiagnostics.log("cache", "hit", "engine", engine.value);
					callback.onResult(cached.subject, cached.html, null);
					return;
				}
				TranslationDiagnostics.log("cache", "miss", "engine", engine.value, "generation", generation);
				ArrayList<ResultCallback> waiting = sharedTranslations.get(sharedKey);
				if (waiting != null) {
					waiting.add(callback);
					return;
				}
				waiting = new ArrayList<>();
				waiting.add(callback);
				sharedTranslations.put(sharedKey, waiting);
				translateWhenReady(engine, direction, subject, html, (translatedSubject, translatedHtml, error) -> {
					ArrayList<ResultCallback> callbacks = sharedTranslations.remove(sharedKey);
					// Commit before showing a freshly translated result. Closing immediately after display
					// must not kill a still-queued cache write. A cache error still permits displaying the result.
					Runnable deliver = () -> {
						boolean current = generation == cacheGeneration.get();
						if (callbacks != null) for (ResultCallback resultCallback : callbacks) {
							resultCallback.onResult(current ? translatedSubject : null, current ? translatedHtml : null,
									current ? error : "Translator unloaded");
						}
					};
					if (generation == cacheGeneration.get() && error == null && translatedHtml != null) {
						cacheExecutor.execute(() -> {
							if (generation == cacheGeneration.get()) persistentCache.put(key, translatedSubject, translatedHtml);
							handler.post(deliver);
						});
					} else deliver.run();
				}, generation, SystemClock.elapsedRealtime() + 15000L, false);
			});
		});
	}

	private void translateWhenReady(TranslationEngine engine, TranslationModel.Direction direction,
			String subject, String html, ResultCallback callback, long generation, long deadline, boolean waited) {
		if (generation != cacheGeneration.get() || engine != getCurrentEngine() || !isEnabledForDirection(direction)) {
			callback.onResult(null, null, "Translator unloaded");
			return;
		}
		TranslationModelManager.State state = getEngineState(engine, direction);
		if (state == TranslationModelManager.State.CHECKING && SystemClock.elapsedRealtime() < deadline) {
			if (!waited) TranslationDiagnostics.log("cold_start", "waiting", "engine", engine.value);
			handler.postDelayed(() -> translateWhenReady(engine, direction, subject, html, callback,
					generation, deadline, true), 250L);
		} else if (state == TranslationModelManager.State.INSTALLED) {
			TranslationDiagnostics.log("cold_start", "ready", "engine", engine.value, "waited", waited);
			translate(engine, direction, subject, html, callback);
		} else {
			TranslationDiagnostics.error("cold_start", "unavailable", "engine", engine.value, "state", state.name());
			callback.onResult(null, null, "Translation package is unavailable");
		}
	}

	private void translate(TranslationEngine engine, TranslationModel.Direction direction, String subject, String html,
			ResultCallback resultCallback) {
		handler.removeCallbacks(idleDisconnectRunnable);
		long id = nextRequestId.getAndIncrement();
		calls.put(id, new PendingCall(id, engine, direction, subject != null ? subject : "",
				html != null ? html : "", resultCallback));
		TranslationDiagnostics.log("controller", "request_enqueued", "request_id", id, "engine", engine.value,
				"direction", direction.name(), "subject_chars", TranslationDiagnostics.length(subject),
				"html_chars", TranslationDiagnostics.length(html), "pending", calls.size());
		handler.postDelayed(() -> {
			if (calls.containsKey(id)) {
				TranslationDiagnostics.error("controller", "request_timeout", "request_id", id,
						"pending", calls.size());
			}
			finish(id, null, null, "Translation timed out");
		}, REQUEST_TIMEOUT_MS);
		if (service != null) {
			dispatch(calls.get(id));
		} else if (!binding) {
			TranslationDiagnostics.log("controller", "service_bind_start", "request_id", id);
			binding = application.bindService(new Intent(application, TranslationService.class), connection,
					Context.BIND_AUTO_CREATE);
			TranslationDiagnostics.log("controller", "service_bind_result", "success", binding);
			if (!binding) {
				failAll("Cannot start translation service");
			}
		}
	}

	private void dispatchPending() {
		for (PendingCall call : new ArrayList<>(calls.values())) {
			dispatch(call);
		}
	}

	private void dispatch(PendingCall call) {
		if (service == null || call == null) {
			TranslationDiagnostics.log("controller", "dispatch_skipped", "has_service", service != null,
					"has_call", call != null);
			return;
		}
		try {
			TranslationDiagnostics.log("controller", "dispatch", "request_id", call.id,
					"html_chars", call.html.length());
			service.translate(call.id, call.engine.value, call.direction.sourceLanguage, call.direction.targetLanguage,
					call.subject, call.html, callback);
		} catch (RemoteException e) {
			TranslationDiagnostics.error("controller", "dispatch_error", "request_id", call.id,
					"error", e.getClass().getSimpleName());
			finish(call.id, null, null, "Translation service failed");
		}
	}

	private void finish(long requestId, String translatedSubject, String translatedHtml, String error) {
		PendingCall call = calls.remove(requestId);
		if (call != null) {
			TranslationDiagnostics.log("controller", "request_result", "request_id", requestId,
					"success", error == null && translatedHtml != null,
					"subject_chars", TranslationDiagnostics.length(translatedSubject),
					"html_chars", TranslationDiagnostics.length(translatedHtml),
					"error", TranslationDiagnostics.safeError(error), "remaining", calls.size());
			call.callback.onResult(translatedSubject, translatedHtml, error);
			if (calls.isEmpty()) {
				scheduleIdleDisconnect();
			}
		} else {
			TranslationDiagnostics.log("controller", "late_result_ignored", "request_id", requestId,
					"error", TranslationDiagnostics.safeError(error));
		}
	}

	private void failAll(String message) {
		TranslationDiagnostics.error("controller", "fail_all", "pending", calls.size(),
				"error", TranslationDiagnostics.safeError(message));
		for (PendingCall call : new ArrayList<>(calls.values())) {
			finish(call.id, null, null, message);
		}
	}

	private void scheduleIdleDisconnect() {
		handler.removeCallbacks(idleDisconnectRunnable);
		handler.postDelayed(idleDisconnectRunnable, IDLE_DISCONNECT_DELAY_MS);
	}

	private void disconnect() {
		handler.removeCallbacks(idleDisconnectRunnable);
		TranslationDiagnostics.log("controller", "disconnect", "has_service", service != null,
				"binding", binding, "pending", calls.size());
		if (service != null) {
			try {
				service.unload();
			} catch (RemoteException ignored) {}
		}
		if (binding || service != null) {
			try {
				application.unbindService(connection);
			} catch (IllegalArgumentException ignored) {}
		}
		service = null;
		binding = false;
	}
}
