package com.mishiranu.dashchan.content.translation;

import android.content.Context;

public final class GeminiNanoTranslationBridge {
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

	public static boolean isAvailable() {
		return false;
	}

	public static Snapshot getSnapshot(TranslationModel.Direction direction) {
		return new Snapshot(TranslationModelManager.State.NOT_INSTALLED, 0, 0L, 0L,
				false, false, null);
	}

	public static void register(Listener listener) {}

	public static void unregister(Listener listener) {}

	public static void refresh() {}

	public static void download(TranslationModel.Direction direction) {}

	public void translate(Context context, TranslationModel.Direction direction, String subject, String html,
			Callback callback) {
		callback.onError("Gemini Nano is unavailable");
	}

	public void unload() {}
}
