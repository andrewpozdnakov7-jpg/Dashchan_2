package io.dashchan2.addon.translation;

interface IGoogleTranslationCallback {
	void onStatus(int state, long downloadedBytes, long totalBytes, String error);
	void onTranslation(in List<String> translations);
	void onError(String message);
}
