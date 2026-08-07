package com.mishiranu.dashchan.content.translation;

import android.content.Context;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.google.mlkit.common.MlKit;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.mishiranu.dashchan.content.MainApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public final class GoogleTranslationBridge {
	public static final long APPROXIMATE_MODEL_SIZE = 30L * 1024L * 1024L;

	private static final String MODEL_PREFERENCES = "google_translation_models";
	private static final String KEY_RUSSIAN_MODEL_INSTALLED = "russian_installed";
	private static final TranslateRemoteModel RUSSIAN_MODEL = new TranslateRemoteModel.Builder(
			TranslateLanguage.RUSSIAN).build();
	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
	private static final long DOWNLOAD_PROGRESS_INTERVAL_MS = 1000L;
	private static final Runnable DOWNLOAD_PROGRESS_UPDATER = GoogleTranslationBridge::updateDownloadProgress;
	private static Snapshot snapshot;
	private static boolean statusQueryStarted;
	private static long downloadStartRxBytes = TrafficStats.UNSUPPORTED;

	public static final class Snapshot {
		public final TranslationModelManager.State state;
		public final int progress;
		public final long downloadedBytes;
		public final String error;

		private Snapshot(TranslationModelManager.State state, int progress, long downloadedBytes, String error) {
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

	private Translator translator;
	private TranslationModel.Direction translatorDirection;
	private int generation;

	public static boolean isAvailable() {
		return true;
	}

	public static synchronized Snapshot getSnapshot(TranslationModel.Direction direction) {
		if (snapshot == null) {
			try {
				boolean installed = getPreferences().getBoolean(KEY_RUSSIAN_MODEL_INSTALLED, false);
				snapshot = new Snapshot(installed ? TranslationModelManager.State.INSTALLED
						: TranslationModelManager.State.CHECKING, installed ? 100 : 0, 0L, null);
			} catch (RuntimeException | LinkageError error) {
				statusQueryStarted = true;
				snapshot = createErrorSnapshot(error);
			}
		}
		if (!statusQueryStarted) {
			statusQueryStarted = true;
			try {
				getRemoteModelManager().isModelDownloaded(RUSSIAN_MODEL)
						.addOnSuccessListener(installed -> setInstalled(Boolean.TRUE.equals(installed)))
						.addOnFailureListener(error -> setSnapshot(createErrorSnapshot(error)));
			} catch (RuntimeException | LinkageError error) {
				snapshot = createErrorSnapshot(error);
			}
		}
		return snapshot;
	}

	public static void register(Listener listener) {
		LISTENERS.add(listener);
	}

	public static void unregister(Listener listener) {
		LISTENERS.remove(listener);
	}

	public static synchronized void download(TranslationModel.Direction direction) {
		Snapshot current = getSnapshot(direction);
		if (current.state == TranslationModelManager.State.DOWNLOADING ||
				current.state == TranslationModelManager.State.INSTALLED) {
			return;
		}
		downloadStartRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
		setSnapshotLocked(new Snapshot(TranslationModelManager.State.DOWNLOADING, 0, 0L, null));
		MAIN_HANDLER.removeCallbacks(DOWNLOAD_PROGRESS_UPDATER);
		MAIN_HANDLER.postDelayed(DOWNLOAD_PROGRESS_UPDATER, DOWNLOAD_PROGRESS_INTERVAL_MS);
		DownloadConditions conditions = new DownloadConditions.Builder().build();
		try {
			getRemoteModelManager().download(RUSSIAN_MODEL, conditions)
					.addOnSuccessListener(ignored -> setInstalled(true))
					.addOnFailureListener(error -> setSnapshot(createErrorSnapshot(error)));
		} catch (RuntimeException | LinkageError error) {
			setSnapshotLocked(createErrorSnapshot(error));
		}
	}

	public static synchronized void delete(TranslationModel.Direction direction, Runnable onSuccess) {
		Snapshot current = getSnapshot(direction);
		if (current.state == TranslationModelManager.State.DOWNLOADING) {
			return;
		}
		TranslationController.getInstance().unload();
		try {
			getRemoteModelManager().deleteDownloadedModel(RUSSIAN_MODEL)
					.addOnSuccessListener(ignored -> {
						setInstalled(false);
						if (onSuccess != null) {
							onSuccess.run();
						}
					})
					.addOnFailureListener(error -> setSnapshot(createErrorSnapshot(error)));
		} catch (RuntimeException | LinkageError error) {
			setSnapshotLocked(createErrorSnapshot(error));
		}
	}

	public void translate(Context context, TranslationModel.Direction direction, String subject, String html,
			Callback callback) {
		RemoteModelManager modelManager;
		Translator translator;
		try {
			modelManager = getRemoteModelManager(context);
			translator = obtainTranslator(direction);
		} catch (RuntimeException | LinkageError error) {
			callback.onError(getErrorMessage(error));
			return;
		}
		final int currentGeneration = generation;
		try {
			modelManager.isModelDownloaded(RUSSIAN_MODEL)
					.addOnSuccessListener(installed -> {
						if (currentGeneration != generation) {
							return;
						}
						if (!Boolean.TRUE.equals(installed)) {
							setInstalled(false);
							callback.onError("Google language package is not installed");
							return;
						}
						translateContents(translator, subject, html, currentGeneration, callback);
					})
					.addOnFailureListener(error -> {
						if (currentGeneration == generation) {
							callback.onError(getErrorMessage(error));
						}
					});
		} catch (RuntimeException | LinkageError error) {
			callback.onError(getErrorMessage(error));
		}
	}

	public void unload() {
		generation++;
		if (translator != null) {
			translator.close();
			translator = null;
		}
		translatorDirection = null;
	}

	private Translator obtainTranslator(TranslationModel.Direction direction) {
		if (translator == null || translatorDirection != direction) {
			unload();
			TranslatorOptions options = new TranslatorOptions.Builder()
					.setSourceLanguage(direction == TranslationModel.Direction.EN_RU
							? TranslateLanguage.ENGLISH : TranslateLanguage.RUSSIAN)
					.setTargetLanguage(direction == TranslationModel.Direction.EN_RU
							? TranslateLanguage.RUSSIAN : TranslateLanguage.ENGLISH)
					.build();
			translator = Translation.getClient(options);
			translatorDirection = direction;
		}
		return translator;
	}

	private void translateContents(Translator translator, String subject, String html, int currentGeneration,
			Callback callback) {
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
		String[] results = new String[segments.size()];
		AtomicInteger remaining = new AtomicInteger(segments.size());
		AtomicBoolean completed = new AtomicBoolean();
		for (int i = 0; i < segments.size(); i++) {
			final int index = i;
			translator.translate(segments.get(i).text).addOnSuccessListener(result -> {
				results[index] = result;
				if (remaining.decrementAndGet() == 0 && completed.compareAndSet(false, true) &&
						currentGeneration == generation) {
					String translatedSubject = subject;
					for (int resultIndex = 0; resultIndex < segments.size(); resultIndex++) {
						Segment segment = segments.get(resultIndex);
						String translated = segment.prefix + results[resultIndex] + segment.suffix;
						if (segment.textNode != null) {
							segment.textNode.text(translated);
						} else {
							translatedSubject = translated;
						}
					}
					callback.onSuccess(translatedSubject, document.body().html());
				}
			}).addOnFailureListener(error -> {
				if (completed.compareAndSet(false, true) && currentGeneration == generation) {
					callback.onError(getErrorMessage(error));
				}
			});
		}
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
		if (start == end) {
			return null;
		}
		return new Segment(textNode, value.substring(0, start), value.substring(start, end), value.substring(end));
	}

	private static boolean isSpace(char value) {
		return Character.isWhitespace(value) || Character.isSpaceChar(value);
	}

	private static synchronized void setInstalled(boolean installed) {
		try {
			getPreferences().edit().putBoolean(KEY_RUSSIAN_MODEL_INSTALLED, installed).apply();
			setSnapshotLocked(new Snapshot(installed ? TranslationModelManager.State.INSTALLED
					: TranslationModelManager.State.NOT_INSTALLED, installed ? 100 : 0, 0L, null));
		} catch (RuntimeException | LinkageError error) {
			setSnapshotLocked(createErrorSnapshot(error));
		}
	}

	private static synchronized void updateDownloadProgress() {
		if (snapshot == null || snapshot.state != TranslationModelManager.State.DOWNLOADING) {
			return;
		}
		long currentRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
		long downloadedBytes = downloadStartRxBytes != TrafficStats.UNSUPPORTED &&
				currentRxBytes != TrafficStats.UNSUPPORTED && currentRxBytes >= downloadStartRxBytes
				? currentRxBytes - downloadStartRxBytes : 0L;
		if (downloadedBytes != snapshot.downloadedBytes) {
			int progress = (int) Math.min(99L, downloadedBytes * 100L / APPROXIMATE_MODEL_SIZE);
			setSnapshotLocked(new Snapshot(TranslationModelManager.State.DOWNLOADING,
					progress, downloadedBytes, null));
		}
		MAIN_HANDLER.postDelayed(DOWNLOAD_PROGRESS_UPDATER, DOWNLOAD_PROGRESS_INTERVAL_MS);
	}

	private static synchronized void setSnapshot(Snapshot newSnapshot) {
		setSnapshotLocked(newSnapshot);
	}

	private static void setSnapshotLocked(Snapshot newSnapshot) {
		snapshot = newSnapshot;
		if (newSnapshot.state != TranslationModelManager.State.DOWNLOADING) {
			MAIN_HANDLER.removeCallbacks(DOWNLOAD_PROGRESS_UPDATER);
			downloadStartRxBytes = TrafficStats.UNSUPPORTED;
		}
		MAIN_HANDLER.post(() -> {
			for (Listener listener : LISTENERS) {
				listener.onGoogleTranslationModelChanged(newSnapshot);
			}
		});
	}

	private static android.content.SharedPreferences getPreferences() {
		Context context = MainApplication.getInstance();
		if (context == null) {
			throw new IllegalStateException("Application context is unavailable");
		}
		return context.getSharedPreferences(MODEL_PREFERENCES, Context.MODE_PRIVATE);
	}

	private static RemoteModelManager getRemoteModelManager() {
		return getRemoteModelManager(MainApplication.getInstance());
	}

	private static RemoteModelManager getRemoteModelManager(Context context) {
		RemoteModelManager manager;
		try {
			manager = RemoteModelManager.getInstance();
		} catch (IllegalStateException error) {
			if (context == null) {
				throw error;
			}
			Context applicationContext = context.getApplicationContext();
			MlKit.initialize(applicationContext != null ? applicationContext : context);
			manager = RemoteModelManager.getInstance();
		}
		if (manager == null) {
			throw new IllegalStateException("Google ML Kit is unavailable");
		}
		return manager;
	}

	private static Snapshot createErrorSnapshot(Throwable error) {
		return new Snapshot(TranslationModelManager.State.ERROR, 0, 0L, getErrorMessage(error));
	}

	private static String getErrorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
	}
}
