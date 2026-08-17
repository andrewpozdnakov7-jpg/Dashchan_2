package io.dashchan2.addon.translation;

import io.dashchan2.addon.translation.IGoogleTranslationCallback;

interface IGoogleTranslationService {
	int getProtocolVersion();
	void getModelStatus(String sourceLanguage, String targetLanguage,
			IGoogleTranslationCallback callback);
	void downloadModel(String sourceLanguage, String targetLanguage,
			IGoogleTranslationCallback callback);
	void deleteModel(String sourceLanguage, String targetLanguage,
			IGoogleTranslationCallback callback);
	void translate(String sourceLanguage, String targetLanguage, in List<String> texts,
			IGoogleTranslationCallback callback);
}
