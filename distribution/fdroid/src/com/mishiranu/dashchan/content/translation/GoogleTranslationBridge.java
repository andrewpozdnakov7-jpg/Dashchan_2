package com.mishiranu.dashchan.content.translation;

import android.content.Context;

public final class GoogleTranslationBridge {
	public static final long APPROXIMATE_MODEL_SIZE = 30L * 1024L * 1024L;
	public static final long APPROXIMATE_ADDON_SIZE = 50L * 1024L * 1024L;

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

	public static boolean isAvailable() {
		return false;
	}

	public static Snapshot getSnapshot(TranslationModel.Direction direction) {
		return new Snapshot(false, TranslationModelManager.State.NOT_INSTALLED, 0, 0L, null);
	}

	public static void refresh(TranslationModel.Direction direction) {}

	public static void register(Listener listener) {}

	public static void unregister(Listener listener) {}

	public static void downloadAddon() {}

	public static void download(TranslationModel.Direction direction) {}

	public static void delete(TranslationModel.Direction direction, Runnable onSuccess) {}

	public void translate(Context context, TranslationModel.Direction direction, String subject, String html,
			Callback callback) {
		callback.onError("Google translation is unavailable");
	}

	public void unload() {}
}
