package com.mishiranu.dashchan.content.translation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import chan.content.ChanManager;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.UpdaterActivity;
import io.dashchan2.addon.translation.IGoogleTranslationCallback;
import io.dashchan2.addon.translation.IGoogleTranslationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public final class GoogleTranslationBridge {
	public static final long APPROXIMATE_MODEL_SIZE = 30L * 1024L * 1024L;
	public static final long APPROXIMATE_ADDON_SIZE = 50L * 1024L * 1024L;

	private static final String ADDON_PACKAGE = "io.dashchan2.addon.googletranslate";
	private static final String ADDON_SERVICE = ADDON_PACKAGE + ".GoogleTranslationService";
	private static final int PROTOCOL_VERSION = 1;
	private static final int ADDON_STATE_NOT_INSTALLED = 0;
	private static final int ADDON_STATE_CHECKING = 1;
	private static final int ADDON_STATE_DOWNLOADING = 2;
	private static final int ADDON_STATE_INSTALLED = 3;
	private static final int ADDON_STATE_ERROR = 4;

	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
	private static final ArrayList<ServiceOperation> PENDING_OPERATIONS = new ArrayList<>();
	private static IGoogleTranslationService service;
	private static boolean binding;
	private static boolean statusQueryStarted;
	private static Snapshot snapshot;

	public static final class Snapshot {
		public final boolean addonInstalled;
		public final TranslationModelManager.State state;
		public final int progress;
		public final long downloadedBytes;
		public final String error;

		private Snapshot(boolean addonInstalled, TranslationModelManager.State state, int progress,
				long downloadedBytes, String error) {
			this.addonInstalled = addonInstalled;
			this.state = state;
			this.progress = progress;
			this.downloadedBytes = downloadedBytes;
			this.error = error;
		}
	}

	public interface Listener {
		void onGoogleTranslationModelChanged(Snapshot snapshot);
	}

	public interface Callback {
		void onSuccess(String translatedSubject, String translatedHtml);
		void onError(String message);
	}

	private interface ServiceOperation {
		void run(IGoogleTranslationService service) throws RemoteException;
		void onError(String message);
	}

	private static final class Segment {
		public final TextNode textNode;
		public final String prefix;
		public final String text;
		public final String suffix;

		private Segment(TextNode textNode, String prefix, String text, String suffix) {
			this.textNode = textNode;
			this.prefix = prefix;
			this.text = text;
			this.suffix = suffix;
		}
	}

	private int generation;

	public static boolean isAvailable() {
		return BuildConfig.ENABLE_GOOGLE_TRANSLATION;
	}

	public static boolean isAddonInstalled() {
		return getAddonError() == null;
	}

	public static synchronized Snapshot getSnapshot(TranslationModel.Direction direction) {
		String addonError = getAddonError();
		if (addonError != null) {
			statusQueryStarted = false;
			snapshot = new Snapshot(false, TranslationModelManager.State.NOT_INSTALLED, 0, 0L,
					"not_installed".equals(addonError) ? null : addonError);
			return snapshot;
		}
		if (snapshot == null || !snapshot.addonInstalled) {
			snapshot = new Snapshot(true, TranslationModelManager.State.CHECKING, 0, 0L, null);
			statusQueryStarted = false;
		}
		if (!statusQueryStarted) {
			statusQueryStarted = true;
			requestStatus(direction);
		}
		return snapshot;
	}

	public static synchronized void refresh(TranslationModel.Direction direction) {
		statusQueryStarted = false;
		snapshot = null;
		getSnapshot(direction);
	}

	public static void register(Listener listener) {
		LISTENERS.add(listener);
	}

	public static void unregister(Listener listener) {
		LISTENERS.remove(listener);
	}

	public static void downloadAddon() {
		UpdaterActivity.startUpdaterAfterExtensionInstallConsent(Collections.singletonList(
				new UpdaterActivity.Request("google-translator", BuildConfig.GOOGLE_TRANSLATION_ADDON_VERSION,
						Uri.parse(BuildConfig.GOOGLE_TRANSLATION_ADDON_DOWNLOAD_URL),
						null,
						ChanManager.getInstance().getApplicationFingerprints())));
	}

	public static void download(TranslationModel.Direction direction) {
		if (!isAddonInstalled()) {
			return;
		}
		setSnapshot(new Snapshot(true, TranslationModelManager.State.DOWNLOADING, 0, 0L, null));
		withService(new ServiceOperation() {
			@Override
			public void run(IGoogleTranslationService service) throws RemoteException {
				service.downloadModel(direction.sourceLanguage, direction.targetLanguage,
						createStatusCallback());
			}

			@Override
			public void onError(String message) {
				setSnapshot(new Snapshot(true, TranslationModelManager.State.ERROR, 0, 0L, message));
			}
		});
	}

	public static void delete(TranslationModel.Direction direction, Runnable onSuccess) {
		if (!isAddonInstalled()) {
			return;
		}
		withService(new ServiceOperation() {
			@Override
			public void run(IGoogleTranslationService service) throws RemoteException {
				service.deleteModel(direction.sourceLanguage, direction.targetLanguage,
						new IGoogleTranslationCallback.Stub() {
							@Override
							public void onStatus(int state, long downloadedBytes, long totalBytes, String error) {
								handleStatus(state, downloadedBytes, totalBytes, error);
								if (state == ADDON_STATE_NOT_INSTALLED && onSuccess != null) {
									MAIN_HANDLER.post(onSuccess);
								}
							}

							@Override
							public void onTranslation(List<String> translations) {}

							@Override
							public void onError(String message) {
								setSnapshot(new Snapshot(true, TranslationModelManager.State.ERROR,
										0, 0L, message));
							}
						});
			}

			@Override
			public void onError(String message) {
				setSnapshot(new Snapshot(true, TranslationModelManager.State.ERROR, 0, 0L, message));
			}
		});
	}

	public void translate(Context context, TranslationModel.Direction direction, String subject, String html,
			Callback callback) {
		if (!isAddonInstalled()) {
			callback.onError("Google translation add-on is not installed");
			return;
		}
		final int currentGeneration = generation;
		Document document = Jsoup.parseBodyFragment(html);
		document.outputSettings().prettyPrint(false);
		ArrayList<Segment> segments = new ArrayList<>();
		Segment subjectSegment = createSegment(null, subject);
		if (subjectSegment != null) {
			segments.add(subjectSegment);
		}
		collectSegments(document.body(), segments);
		if (segments.isEmpty()) {
			callback.onSuccess(subject, html);
			return;
		}
		ArrayList<String> texts = new ArrayList<>(segments.size());
		for (Segment segment : segments) {
			texts.add(segment.text);
		}
		withService(new ServiceOperation() {
			@Override
			public void run(IGoogleTranslationService service) throws RemoteException {
				service.translate(direction.sourceLanguage, direction.targetLanguage, texts,
						new IGoogleTranslationCallback.Stub() {
							@Override
							public void onStatus(int state, long downloadedBytes, long totalBytes, String error) {}

							@Override
							public void onTranslation(List<String> translations) {
								MAIN_HANDLER.post(() -> {
									if (currentGeneration != generation) {
										return;
									}
									if (translations == null || translations.size() != segments.size()) {
										callback.onError("Invalid response from Google translation add-on");
										return;
									}
									String translatedSubject = subject;
									for (int i = 0; i < segments.size(); i++) {
										Segment segment = segments.get(i);
										String translated = segment.prefix + translations.get(i) + segment.suffix;
										if (segment.textNode != null) {
											segment.textNode.text(translated);
										} else {
											translatedSubject = translated;
										}
									}
									callback.onSuccess(translatedSubject, document.body().html());
								});
							}

							@Override
							public void onError(String message) {
								MAIN_HANDLER.post(() -> {
									if (currentGeneration == generation) {
										callback.onError(message != null ? message : "Google translation failed");
									}
								});
							}
						});
			}

			@Override
			public void onError(String message) {
				callback.onError(message);
			}
		});
	}

	public void unload() {
		generation++;
	}

	private static void requestStatus(TranslationModel.Direction direction) {
		withService(new ServiceOperation() {
			@Override
			public void run(IGoogleTranslationService service) throws RemoteException {
				service.getModelStatus(direction.sourceLanguage, direction.targetLanguage,
						createStatusCallback());
			}

			@Override
			public void onError(String message) {
				setSnapshot(new Snapshot(true, TranslationModelManager.State.ERROR, 0, 0L, message));
			}
		});
	}

	private static IGoogleTranslationCallback createStatusCallback() {
		return new IGoogleTranslationCallback.Stub() {
			@Override
			public void onStatus(int state, long downloadedBytes, long totalBytes, String error) {
				handleStatus(state, downloadedBytes, totalBytes, error);
			}

			@Override
			public void onTranslation(List<String> translations) {}

			@Override
			public void onError(String message) {
				setSnapshot(new Snapshot(true, TranslationModelManager.State.ERROR, 0, 0L,
						message != null ? message : "Google translation add-on failed"));
			}
		};
	}

	private static void handleStatus(int state, long downloadedBytes, long totalBytes, String error) {
		TranslationModelManager.State mappedState;
		switch (state) {
			case ADDON_STATE_CHECKING: mappedState = TranslationModelManager.State.CHECKING; break;
			case ADDON_STATE_DOWNLOADING: mappedState = TranslationModelManager.State.DOWNLOADING; break;
			case ADDON_STATE_INSTALLED: mappedState = TranslationModelManager.State.INSTALLED; break;
			case ADDON_STATE_ERROR: mappedState = TranslationModelManager.State.ERROR; break;
			default: mappedState = TranslationModelManager.State.NOT_INSTALLED; break;
		}
		long expected = totalBytes > 0L ? totalBytes : APPROXIMATE_MODEL_SIZE;
		int progress = mappedState == TranslationModelManager.State.INSTALLED ? 100
				: mappedState == TranslationModelManager.State.DOWNLOADING
						? (int) Math.min(99L, downloadedBytes * 100L / expected) : 0;
		setSnapshot(new Snapshot(true, mappedState, progress, downloadedBytes, error));
	}

	private static void withService(ServiceOperation operation) {
		MAIN_HANDLER.post(() -> {
			if (service != null) {
				try {
					operation.run(service);
				} catch (RemoteException | RuntimeException e) {
					operation.onError(errorMessage(e));
				}
				return;
			}
			PENDING_OPERATIONS.add(operation);
			if (binding) {
				return;
			}
			String addonError = getAddonError();
			if (addonError != null) {
				failPending("not_installed".equals(addonError)
						? "Google translation add-on is not installed" : addonError);
				return;
			}
			binding = true;
			Context context = MainApplication.getInstance();
			Intent intent = new Intent().setComponent(new ComponentName(ADDON_PACKAGE, ADDON_SERVICE));
			try {
				if (!context.bindService(intent, SERVICE_CONNECTION, Context.BIND_AUTO_CREATE)) {
					binding = false;
					failPending("Cannot connect to Google translation add-on");
				}
			} catch (RuntimeException e) {
				binding = false;
				failPending(errorMessage(e));
			}
		});
	}

	private static final ServiceConnection SERVICE_CONNECTION = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			MAIN_HANDLER.post(() -> {
				binding = false;
				IGoogleTranslationService candidate = IGoogleTranslationService.Stub.asInterface(binder);
				try {
					if (candidate == null || candidate.getProtocolVersion() != PROTOCOL_VERSION) {
						failPending("Unsupported Google translation add-on version");
						return;
					}
					service = candidate;
					ArrayList<ServiceOperation> operations = new ArrayList<>(PENDING_OPERATIONS);
					PENDING_OPERATIONS.clear();
					for (ServiceOperation operation : operations) {
						try {
							operation.run(service);
						} catch (RemoteException | RuntimeException e) {
							operation.onError(errorMessage(e));
						}
					}
				} catch (RemoteException | RuntimeException e) {
					failPending(errorMessage(e));
				}
			});
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			MAIN_HANDLER.post(() -> {
				service = null;
				binding = false;
				failPending("Google translation add-on disconnected");
			});
		}
	};

	private static void failPending(String message) {
		ArrayList<ServiceOperation> operations = new ArrayList<>(PENDING_OPERATIONS);
		PENDING_OPERATIONS.clear();
		for (ServiceOperation operation : operations) {
			operation.onError(message);
		}
	}

	private static String getAddonError() {
		Context context = MainApplication.getInstance();
		PackageManager packageManager = context.getPackageManager();
		try {
			packageManager.getPackageInfo(ADDON_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
		} catch (PackageManager.NameNotFoundException e) {
			return "not_installed";
		}
		return packageManager.checkSignatures(context.getPackageName(), ADDON_PACKAGE) ==
				PackageManager.SIGNATURE_MATCH ? null : "Google translation add-on has an invalid signature";
	}

	private static synchronized void setSnapshot(Snapshot newSnapshot) {
		snapshot = newSnapshot;
		MAIN_HANDLER.post(() -> {
			for (Listener listener : LISTENERS) {
				listener.onGoogleTranslationModelChanged(newSnapshot);
			}
		});
	}

	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
	}

	private static void collectSegments(Node node, List<Segment> segments) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode) {
				if (!hasBlockedAncestor(child)) {
					Segment segment = createSegment((TextNode) child, ((TextNode) child).getWholeText());
					if (segment != null) {
						segments.add(segment);
					}
				}
			} else {
				collectSegments(child, segments);
			}
		}
	}

	private static boolean hasBlockedAncestor(Node node) {
		for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
			if (parent instanceof Element) {
				String tag = ((Element) parent).tagName();
				if ("a".equals(tag) || "code".equals(tag) || "pre".equals(tag) || "script".equals(tag) ||
						"style".equals(tag)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Segment createSegment(TextNode textNode, String value) {
		int start = 0;
		int end = value.length();
		while (start < end && isSpace(value.charAt(start))) {
			start++;
		}
		while (end > start && isSpace(value.charAt(end - 1))) {
			end--;
		}
		return start == end ? null : new Segment(textNode, value.substring(0, start),
				value.substring(start, end), value.substring(end));
	}

	private static boolean isSpace(char value) {
		return Character.isWhitespace(value) || Character.isSpaceChar(value);
	}
}
