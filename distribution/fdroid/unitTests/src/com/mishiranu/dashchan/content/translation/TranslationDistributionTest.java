package com.mishiranu.dashchan.content.translation;

import static org.junit.Assert.*;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** Mozilla and Material 3 are shared; proprietary bridges, predictive Back and security are excluded. */
public class TranslationDistributionTest {
    @Test public void mozillaAndMaterialAreAvailableWithoutGooglePredictiveBackOrSecurity() {
        assertTrue(BuildConfig.ENABLE_LOCAL_TRANSLATION);
        assertTrue(TranslationEngine.MOZILLA.isAvailable());
        assertFalse(Preferences.DEFAULT_LOCAL_TRANSLATION);
        assertFalse(BuildConfig.ENABLE_GOOGLE_TRANSLATION);
        assertFalse(BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION);
        assertFalse(GoogleTranslationBridge.isAvailable());
        assertFalse(GeminiNanoTranslationBridge.isAvailable());
        assertTrue(BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
        assertTrue(Preferences.DEFAULT_NEW_INTERFACE_MOTION);
        assertTrue(Preferences.DEFAULT_NEW_POSTING_FORM);
        assertTrue(Preferences.DEFAULT_NEW_VIDEO_PLAYER);
        assertFalse(BuildConfig.ENABLE_PREDICTIVE_BACK);
        assertFalse(BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
    }
}
