package com.mishiranu.dashchan.content.translation;

import android.content.Context;
import com.mishiranu.dashchan.R;

public enum TranslationEngine {
	MOZILLA("mozilla", R.string.translation_engine_mozilla),
	GOOGLE("google", R.string.translation_engine_google),
	GEMINI_NANO("gemini_nano", R.string.translation_engine_gemini_nano);

	public final String value;
	public final int titleResId;

	TranslationEngine(String value, int titleResId) {
		this.value = value;
		this.titleResId = titleResId;
	}

	public String getDisplayName(Context context) {
		return context.getString(titleResId);
	}

	public String getCacheKey(TranslationModel.Direction direction) {
		return value + ":" + direction.id;
	}

	public boolean isAvailable() {
		switch (this) {
			case GOOGLE: {
				return GoogleTranslationBridge.isAvailable();
			}
			case GEMINI_NANO: {
				return GeminiNanoTranslationBridge.isAvailable();
			}
			default: {
				return true;
			}
		}
	}

	public static TranslationEngine fromValue(String value) {
		for (TranslationEngine engine : values()) {
			if (engine.value.equals(value)) {
				return engine;
			}
		}
		return MOZILLA;
	}
}
