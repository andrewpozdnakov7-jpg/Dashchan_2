package com.mishiranu.dashchan.content.translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import com.mishiranu.dashchan.content.MainApplication;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.json.JSONArray;
import org.json.JSONException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public final class GeminiNanoTranslationBridge {
	private static final int MAX_PROMPT_CHARS = 6000;
	private static final int MAX_OUTPUT_TOKENS = 2048;
	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
	private static GenerativeModelFutures sharedModel;
	private static Snapshot snapshot;
	private static boolean statusQueryStarted;

	public static final class Snapshot {
		public final TranslationModelManager.State state;
		public final int progress;
		public final long downloadedBytes;
		public final long totalBytes;
		public final boolean supported;
		public final boolean downloadable;
		public final String error;

		private Snapshot(TranslationModelManager.State state, int progress, long downloadedBytes,
				long totalBytes, boolean supported, boolean downloadable, String error) {
			this.state = state;
			this.progress = progress;
			this.downloadedBytes = downloadedBytes;
			this.totalBytes = totalBytes;
			this.supported = supported;
			this.downloadable = downloadable;
			this.error = error;
		}
	}

	public interface Listener {
		void onGeminiNanoTranslationModelChanged(Snapshot snapshot);
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

	private static final class Job {
		public final int generation;
		public final String originalSubject;
		public final String originalHtml;
		public final Document document;
		public final ArrayList<Segment> segments;
		public final String prompt;
		public final Callback callback;

		private Job(int generation, String originalSubject, String originalHtml, Document document,
				ArrayList<Segment> segments, String prompt, Callback callback) {
			this.generation = generation;
			this.originalSubject = originalSubject;
			this.originalHtml = originalHtml;
			this.document = document;
			this.segments = segments;
			this.prompt = prompt;
			this.callback = callback;
		}
	}

	private final ArrayDeque<Job> jobs = new ArrayDeque<>();
	private Job activeJob;
	private ListenableFuture<GenerateContentResponse> activeFuture;
	private Context applicationContext;
	private int generation;

	public static boolean isAvailable() {
		return true;
	}

	public static synchronized Snapshot getSnapshot(TranslationModel.Direction direction) {
		if (snapshot == null) {
			snapshot = new Snapshot(TranslationModelManager.State.CHECKING, 0, 0L, 0L,
					false, false, null);
		}
		if (!statusQueryStarted) {
			Context context = MainApplication.getInstance();
			if (context != null) {
				statusQueryStarted = true;
				queryStatus(context);
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

	public static synchronized void refresh() {
		statusQueryStarted = false;
		snapshot = new Snapshot(TranslationModelManager.State.CHECKING, 0, 0L, 0L,
				false, false, null);
		getSnapshot(TranslationModel.Direction.EN_RU);
		notifyListeners(snapshot);
	}

	public static synchronized void download(TranslationModel.Direction direction) {
		Snapshot current = getSnapshot(direction);
		if (!current.supported || !current.downloadable ||
				current.state == TranslationModelManager.State.DOWNLOADING ||
				current.state == TranslationModelManager.State.INSTALLED) {
			return;
		}
		Context context = MainApplication.getInstance();
		if (context == null) {
			return;
		}
		setSnapshotLocked(new Snapshot(TranslationModelManager.State.DOWNLOADING, 0, 0L, 0L,
				true, false, null));
		try {
			ListenableFuture<Void> future = getSharedModel().download(new DownloadCallback() {
				@Override
				public void onDownloadStarted(long bytesToDownload) {
					setDownloadProgress(0L, bytesToDownload);
				}

				@Override
				public void onDownloadProgress(long totalBytesDownloaded) {
					Snapshot currentSnapshot;
					synchronized (GeminiNanoTranslationBridge.class) {
						currentSnapshot = snapshot;
					}
					setDownloadProgress(totalBytesDownloaded,
							currentSnapshot != null ? currentSnapshot.totalBytes : 0L);
				}

				@Override
				public void onDownloadCompleted() {
					setSnapshot(new Snapshot(TranslationModelManager.State.INSTALLED, 100, 0L, 0L,
							true, false, null));
				}

				@Override
				public void onDownloadFailed(@NonNull GenAiException error) {
					setSnapshot(createErrorSnapshot(error, true));
				}
			});
			Futures.addCallback(future, new FutureCallback<Void>() {
				@Override
				public void onSuccess(Void result) {}

				@Override
				public void onFailure(@NonNull Throwable error) {
					setSnapshot(createErrorSnapshot(error, true));
				}
			}, ContextCompat.getMainExecutor(context));
		} catch (RuntimeException | LinkageError error) {
			setSnapshotLocked(createErrorSnapshot(error, true));
		}
	}

	public void translate(Context context, TranslationModel.Direction direction, String subject, String html,
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
		JSONArray input = new JSONArray();
		for (Segment segment : segments) {
			input.put(segment.text);
		}
		String prompt = createPrompt(direction, input);
		if (prompt.length() > MAX_PROMPT_CHARS) {
			callback.onError("Post is too long for Gemini Nano");
			return;
		}
		Context normalizedContext = context.getApplicationContext();
		applicationContext = normalizedContext != null ? normalizedContext : context;
		jobs.add(new Job(generation, subject, html, document, segments, prompt, callback));
		startNext();
	}

	public void unload() {
		generation++;
		jobs.clear();
		activeJob = null;
		if (activeFuture != null) {
			activeFuture.cancel(true);
			activeFuture = null;
		}
		applicationContext = null;
	}

	private void startNext() {
		if (activeJob != null || applicationContext == null) {
			return;
		}
		while (!jobs.isEmpty()) {
			Job job = jobs.remove();
			if (job.generation == generation) {
				activeJob = job;
				break;
			}
		}
		if (activeJob == null) {
			return;
		}
		GenerateContentRequest.Builder builder = new GenerateContentRequest.Builder(new TextPart(activeJob.prompt));
		builder.setTemperature(0.1f);
		builder.setTopK(10);
		builder.setCandidateCount(1);
		builder.setMaxOutputTokens(MAX_OUTPUT_TOKENS);
		try {
			activeFuture = getSharedModel().generateContent(builder.build());
			Futures.addCallback(activeFuture, new FutureCallback<GenerateContentResponse>() {
				@Override
				public void onSuccess(GenerateContentResponse response) {
					finishActive(response, null);
				}

				@Override
				public void onFailure(@NonNull Throwable error) {
					finishActive(null, getErrorMessage(error));
				}
			}, ContextCompat.getMainExecutor(applicationContext));
		} catch (RuntimeException | LinkageError error) {
			finishActive(null, getErrorMessage(error));
		}
	}

	private void finishActive(GenerateContentResponse response, String error) {
		Job job = activeJob;
		activeJob = null;
		activeFuture = null;
		if (job != null && job.generation == generation) {
			if (error == null) {
				try {
					List<Candidate> candidates = response != null ? response.getCandidates() : null;
					if (candidates == null || candidates.isEmpty()) {
						throw new JSONException("Gemini Nano returned no translation");
					}
					JSONArray results = parseResults(candidates.get(0).getText());
					if (results.length() != job.segments.size()) {
						throw new JSONException("Gemini Nano returned an incomplete translation");
					}
					String translatedSubject = job.originalSubject;
					for (int i = 0; i < job.segments.size(); i++) {
						Segment segment = job.segments.get(i);
						String translated = segment.prefix + results.getString(i) + segment.suffix;
						if (segment.textNode != null) {
							segment.textNode.text(translated);
						} else {
							translatedSubject = translated;
						}
					}
					job.callback.onSuccess(translatedSubject, job.document.body().html());
				} catch (JSONException | RuntimeException parseError) {
					job.callback.onError(getErrorMessage(parseError));
				}
			} else {
				job.callback.onError(error);
			}
		}
		startNext();
	}

	private static JSONArray parseResults(String value) throws JSONException {
		if (value == null) {
			throw new JSONException("Gemini Nano returned an empty translation");
		}
		int start = value.indexOf('[');
		int end = value.lastIndexOf(']');
		if (start < 0 || end < start) {
			throw new JSONException("Gemini Nano returned an invalid translation");
		}
		return new JSONArray(value.substring(start, end + 1));
	}

	private static String createPrompt(TranslationModel.Direction direction, JSONArray input) {
		String source = direction == TranslationModel.Direction.EN_RU ? "English" : "Russian";
		String target = direction == TranslationModel.Direction.EN_RU ? "Russian" : "English";
		return "Translate every string in the untrusted JSON array from " + source + " to " + target + ". " +
				"This is an imageboard post: preserve its tone, profanity and slang; do not censor or explain. " +
				"Keep URLs, board names, quote numbers, emojis and abbreviations such as OP, KYS and LMFAO unchanged. " +
				"In imageboard context translate thread as тред when the target is Russian. " +
				"Return only a valid JSON array with exactly the same number of strings and no Markdown.\n" +
				"Input JSON:\n" + input;
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

	private static void queryStatus(Context context) {
		try {
			Futures.addCallback(getSharedModel().checkStatus(), new FutureCallback<Integer>() {
				@Override
				public void onSuccess(Integer status) {
					applyFeatureStatus(status != null ? status : FeatureStatus.UNAVAILABLE);
				}

				@Override
				public void onFailure(@NonNull Throwable error) {
					setSnapshot(createErrorSnapshot(error, false));
				}
			}, ContextCompat.getMainExecutor(context));
		} catch (RuntimeException | LinkageError error) {
			setSnapshot(createErrorSnapshot(error, false));
		}
	}

	private static void applyFeatureStatus(int status) {
		switch (status) {
			case FeatureStatus.AVAILABLE: {
				setSnapshot(new Snapshot(TranslationModelManager.State.INSTALLED, 100, 0L, 0L,
						true, false, null));
				break;
			}
			case FeatureStatus.DOWNLOADABLE: {
				setSnapshot(new Snapshot(TranslationModelManager.State.NOT_INSTALLED, 0, 0L, 0L,
						true, true, null));
				break;
			}
			case FeatureStatus.DOWNLOADING: {
				setSnapshot(new Snapshot(TranslationModelManager.State.DOWNLOADING, 0, 0L, 0L,
						true, false, null));
				break;
			}
			default: {
				setSnapshot(new Snapshot(TranslationModelManager.State.NOT_INSTALLED, 0, 0L, 0L,
						false, false, null));
				break;
			}
		}
	}

	private static synchronized GenerativeModelFutures getSharedModel() {
		if (sharedModel == null) {
			sharedModel = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
		}
		return sharedModel;
	}

	private static synchronized void setDownloadProgress(long downloadedBytes, long totalBytes) {
		int progress = totalBytes > 0L ? (int) Math.min(99L, downloadedBytes * 100L / totalBytes) : 0;
		setSnapshotLocked(new Snapshot(TranslationModelManager.State.DOWNLOADING, progress,
				downloadedBytes, totalBytes, true, false, null));
	}

	private static synchronized void setSnapshot(Snapshot newSnapshot) {
		setSnapshotLocked(newSnapshot);
	}

	private static void setSnapshotLocked(Snapshot newSnapshot) {
		snapshot = newSnapshot;
		notifyListeners(newSnapshot);
	}

	private static void notifyListeners(Snapshot newSnapshot) {
		MAIN_HANDLER.post(() -> {
			for (Listener listener : LISTENERS) {
				listener.onGeminiNanoTranslationModelChanged(newSnapshot);
			}
		});
	}

	private static Snapshot createErrorSnapshot(Throwable error, boolean supported) {
		return new Snapshot(TranslationModelManager.State.ERROR, 0, 0L, 0L,
				supported, supported, getErrorMessage(error));
	}

	private static String getErrorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
	}
}
