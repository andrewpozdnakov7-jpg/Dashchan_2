package com.mishiranu.dashchan.content.service.translation;

interface ITranslationCallback {
	void onSuccess(long requestId, String translatedSubject, String translatedHtml);
	void onError(long requestId, String message);
}
