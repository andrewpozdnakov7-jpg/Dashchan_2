package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

/** Disabled distribution gates must not read a leftover GitHub profile or contact its provider. */
public class SecurityBoundaryTest {
    @Test public void authenticationAndRecoveryDoNotTouchContext() throws Exception {
        assertFalse(SecurityStore.enabled(null));
        assertNull(SecurityStore.authenticationMode((android.content.Context) null));
        assertFalse(SecurityStore.pending(null));
        assertFalse(SecurityStore.locked(null));
        assertFalse(PrivacyReset.requiresRecovery(null));
        assertEquals(PrivacyReset.RecoveryState.NORMAL_START, PrivacyReset.recoveryState(null));
    }

    @Test public void windowAndLaunchGatesAreInert() {
        AppSecurity.install(null, true);
        AppSecurity.protectDialog(null);
        assertFalse(AppSecurity.isProtected(null));
        assertFalse(AppSecurity.shouldSecureWindow(null));
        assertFalse(AppSecurity.deferIfLocked(null));
        assertFalse(SecurityStore.screenCaptureBlocked(null));
        assertFalse(SecurityStore.designCaptureAllowed(null));
        assertNull(AppSecurity.redactNotification(null, (android.app.Notification) null));
    }

    @Test public void backupFolderConsentDoesNotRequireSecurityStorage() throws Exception {
        PreferencesRestore.revokeFolderConsent(null);
    }
}
