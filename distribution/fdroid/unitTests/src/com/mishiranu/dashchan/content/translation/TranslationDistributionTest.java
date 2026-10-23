package com.mishiranu.dashchan.content.translation;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** Mozilla is shared; proprietary bridges and unrelated experimental features are not. */
public class TranslationDistributionTest {
    @Test public void mozillaIsAvailableWithoutGoogleOrExperimentalInterface() {
        assertTrue(BuildConfig.ENABLE_LOCAL_TRANSLATION);
        assertTrue(TranslationEngine.MOZILLA.isAvailable());
        assertFalse(Preferences.DEFAULT_LOCAL_TRANSLATION);
        assertFalse(BuildConfig.ENABLE_GOOGLE_TRANSLATION);
        assertFalse(BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION);
        assertFalse(GoogleTranslationBridge.isAvailable());
        assertFalse(GeminiNanoTranslationBridge.isAvailable());
        assertFalse(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
        assertFalse(BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
    }
}
