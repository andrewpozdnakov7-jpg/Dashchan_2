package com.mishiranu.dashchan.content.translation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import chan.content.Chan;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.service.translation.ITranslationCallback;
import com.mishiranu.dashchan.content.service.translation.ITranslationService;
import com.mishiranu.dashchan.content.service.translation.TranslationService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class TranslationController {
	private static final long REQUEST_TIMEOUT_MS = 180000L;
	private static final long IDLE_DISCONNECT_DELAY_MS = 120000L;

	private interface ResultCallback {
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

	public static boolean isReadyForChan(String chanName) {
		TranslationModel.Direction direction = getCurrentDirection();
		if (!isEnabledForChan(chanName)) {
			return false;
		}
		TranslationEngine engine = getCurrentEngine();
		switch (engine) {
			case GOOGLE: {
				return GoogleTranslationBridge.getSnapshot(direction).state ==
						TranslationModelManager.State.INSTALLED;
			}
			case GEMINI_NANO: {
				return GeminiNanoTranslationBridge.getSnapshot(direction).state ==
						TranslationModelManager.State.INSTALLED;
			}
			default: {
				return TranslationModelManager.getInstance().getSnapshot(direction).state ==
						TranslationModelManager.State.INSTALLED;
			}
		}
	}

	private final MainApplication application = MainApplication.getInstance();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AtomicLong nextRequestId = new AtomicLong(1L);
	private final Map<Long, PendingCall> calls = new HashMap<>();
	private final Map<PostItem, String> pendingPosts = new IdentityHashMap<>();
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
			failAll("Translation service disconnected");
		}
	};

	private TranslationController() {}

	public void requestPostTranslation(PostItem postItem, Chan chan, Runnable onTranslated) {
		TranslationEngine engine = getCurrentEngine();
		TranslationModel.Direction direction = getCurrentDirection();
		String key = engine.getCacheKey(direction);
		if (!isReadyForChan(chan.name) || postItem.hasTranslatedComment(key)) {
			return;
		}
		synchronized (pendingPosts) {
			if (key.equals(pendingPosts.get(postItem))) {
				return;
			}
			pendingPosts.put(postItem, key);
		}
		translate(engine, direction, postItem.getSubject(), postItem.getCommentHtmlForTranslation(),
				(translatedSubject, translatedHtml, error) -> {
			synchronized (pendingPosts) {
				if (key.equals(pendingPosts.get(postItem))) {
					pendingPosts.remove(postItem);
				}
			}
			if (translatedHtml != null) {
				postItem.setTranslatedPost(key, translatedSubject, translatedHtml, chan);
				onTranslated.run();
			}
		});
	}

	public void unload() {
		disconnect();
		failAll("Translator unloaded");
	}

	private void translate(TranslationEngine engine, TranslationModel.Direction direction, String subject, String html,
			ResultCallback resultCallback) {
		handler.removeCallbacks(idleDisconnectRunnable);
		long id = nextRequestId.getAndIncrement();
		calls.put(id, new PendingCall(id, engine, direction, subject != null ? subject : "",
				html != null ? html : "", resultCallback));
		handler.postDelayed(() -> finish(id, null, null, "Translation timed out"), REQUEST_TIMEOUT_MS);
		if (service != null) {
			dispatch(calls.get(id));
		} else if (!binding) {
			binding = application.bindService(new Intent(application, TranslationService.class), connection,
					Context.BIND_AUTO_CREATE);
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
			return;
		}
		try {
			service.translate(call.id, call.engine.value, call.direction.sourceLanguage, call.direction.targetLanguage,
					call.subject, call.html, callback);
		} catch (RemoteException e) {
			finish(call.id, null, null, "Translation service failed");
		}
	}

	private void finish(long requestId, String translatedSubject, String translatedHtml, String error) {
		PendingCall call = calls.remove(requestId);
		if (call != null) {
			call.callback.onResult(translatedSubject, translatedHtml, error);
			if (calls.isEmpty()) {
				scheduleIdleDisconnect();
			}
		}
	}

	private void failAll(String message) {
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
