package com.mishiranu.dashchan.content.security;
import static org.junit.Assert.*;
import org.junit.Test;
import com.mishiranu.dashchan.BuildConfig;
public class SecurityBoundaryTest {
    @Test public void localFeaturesLeaveGoogleServicesAndSelfUpdateDisabled() {
        assertTrue(BuildConfig.ENABLE_APP_SECURITY_SETTINGS);
        assertTrue(BuildConfig.ENABLE_PREDICTIVE_BACK);
        assertFalse(BuildConfig.ALLOW_GMS_SECURITY_PROVIDER);
        assertFalse(BuildConfig.ALLOW_APPLICATION_SELF_UPDATE);
    }
}
