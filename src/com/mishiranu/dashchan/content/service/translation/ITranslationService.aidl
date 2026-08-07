package com.mishiranu.dashchan.content.service.translation;

import com.mishiranu.dashchan.content.service.translation.ITranslationCallback;

interface ITranslationService {
	void translate(long requestId, String sourceLanguage, String targetLanguage, String subject, String html,
			ITranslationCallback callback);
	void unload();
}
