package com.mishiranu.dashchan.content.security;
import static org.junit.Assert.*;
import org.junit.Test;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
public class SecurityBoundaryTest {
    @Test public void localFeaturesDoNotEnableGoogleOrExport() {
        assertTrue(BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
        assertTrue(BuildConfig.ENABLE_PREDICTIVE_BACK);
        assertFalse(Preferences.DEFAULT_PREDICTIVE_BACK);
        assertFalse(BuildConfig.ENABLE_VIDEO_COMPRESSION);
        assertFalse(BuildConfig.ALLOW_GMS_SECURITY_PROVIDER);
        assertFalse(BuildConfig.ALLOW_APPLICATION_SELF_UPDATE);
    }
}
