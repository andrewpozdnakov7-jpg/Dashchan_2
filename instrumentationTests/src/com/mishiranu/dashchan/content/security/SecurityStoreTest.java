package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated security records only. NEVER calls PrivacyReset.run() or deletes a real profile/download tree. */
@RunWith(AndroidJUnit4.class)
public class SecurityStoreTest {
	private Context context;
	private File sandbox;
	@Before public void prepare() throws Exception {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		sandbox = new File(base.getCacheDir(), "security-record-test-" + java.util.UUID.randomUUID());
		assertTrue(sandbox.mkdirs());
		context = new ContextWrapper(base) { @Override public File getNoBackupFilesDir() { return sandbox; } };
		SecurityStore.foreground(context, true);
	}
	@After public void cleanup() throws Exception {
		if (context != null) {
			RememberedAuthentication.revoke(context);
			String alias = SecurityStore.pinAlias(context);
			assertTrue(alias.contains(".test."));
			java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null); keys.deleteEntry(alias);
			SecuritySessionProvider.revoke(context);
		}
		PrivacyResetFiles.delete(sandbox);
	}

	@Test public void writeFailureRevokesMemorySessionEvenWhenOldUnlockedRecordIsReadable() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.unlock(context); assertFalse(SecurityStore.locked(context));
		File blocker = new File(SecurityStore.directory(context), "state.json.new");
		assertTrue(blocker.mkdir());
		assertTrue(new File(blocker, "cannot-replace").createNewFile());
		try { SecurityStore.update(context, s -> { s.put("test_change", true); return null; }); fail("Write must fail"); }
		catch (IOException expected) {}
		assertFalse(SecuritySessionProvider.isUnlocked(context));
		assertTrue(SecurityStore.locked(context));
		SecurityStore.lock(context); // Locking itself does not require a successful disk write.
	}
	private void prepareRecentAuthentication(int minutes) throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.update(context, s -> { s.put(ReauthenticationPolicy.KEY, minutes); return null; });
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		assertFalse(SecurityStore.locked(context));
	}
	@Test public void biometricOnlyWithoutPrimaryStillProtectsProfileAndRejectsPinFallback() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.setPin(context, "654321".toCharArray(), true);
		SecurityStore.update(context, s -> {
			// Synthetic metadata only: no real biometric enrollment or reset operation.
			s.remove("primary"); s.put(AuthenticationPolicy.KEY, "biometric").put("biometric", true);
			s.put("biometric_alias", "unavailable-fixture-key"); return null;
		});
		assertTrue(SecurityStore.enabled(context));
		assertTrue(SecurityStore.locked(context));
		assertTrue(SecurityStore.screenCaptureBlocked(context));
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "123456".toCharArray(), true));
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "654321".toCharArray(), true));
		assertEquals(SecurityStore.Result.ADDITIONAL, SecurityStore.verify(context, "654321".toCharArray(), false));
		try { BiometricAttempt.current(context); fail("Missing/foreign key must not become authenticated"); }
		catch (Exception expected) {}
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void switchingToBiometricWithoutProofCannotRemoveExistingPin() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		String primary = SecurityStore.update(context, s -> s.getJSONObject("primary").toString());
		try {
			SecurityStore.commitAuthenticationMode(context, AuthenticationPolicy.Mode.BIOMETRIC_ONLY, null, null, () -> true);
			fail("Biometric proof missing");
		} catch (IOException expected) {}
		assertEquals(primary, SecurityStore.update(context, s -> s.getJSONObject("primary").toString()));
		assertEquals(AuthenticationPolicy.Mode.PIN_ONLY, SecurityStore.authenticationMode(context));
	}
	@Test public void switchingModeWithExpiredAuthorizationLeavesCredentialsUnchanged() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		String old = SecurityStore.update(context, org.json.JSONObject::toString);
		try {
			SecurityStore.commitAuthenticationMode(context, AuthenticationPolicy.Mode.PIN_ONLY, null, null, () -> false);
			fail("Expired mode change");
		} catch (IOException expected) {}
		assertEquals(old, SecurityStore.update(context, org.json.JSONObject::toString));
	}
	@Test public void explicitInvalidModeCannotBeSavedAsAnUnlockedOrDisabledRecord() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		try { SecurityStore.update(context, s -> { s.put(AuthenticationPolicy.KEY, "biometric"); return null; }); fail("Incomplete mode"); }
		catch (IOException expected) {}
		assertEquals(AuthenticationPolicy.Mode.PIN_ONLY, SecurityStore.authenticationMode(context));
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void recentAuthenticationResumesOnlyInForegroundAndExplicitLockRevokesIt() throws Exception {
		prepareRecentAuthentication(3);
		SecurityStore.foreground(context, false);
		assertTrue(SecurityStore.locked(context));
		SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
		SecurityStore.suspend(context);
		assertTrue(SecurityStore.locked(context));
		SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
		SecurityStore.lock(context);
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void reopeningDoesNotRefreshTheOriginalAuthenticationTime() throws Exception {
		org.junit.Assume.assumeTrue("Expiry fixture needs three minutes of monotonic uptime",
				android.os.SystemClock.elapsedRealtime() > 180_001);
		prepareRecentAuthentication(3);
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context),
				android.os.SystemClock.elapsedRealtime() - 180_001);
		SecurityStore.foreground(context, false);
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void ordinaryUnlockCannotCreateRememberedAuthentication() throws Exception {
		prepareRecentAuthentication(3);
		SecurityStore.lock(context);
		SecurityStore.unlock(context);
		SecurityStore.foreground(context, false);
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void backgroundTimestampIsPersistedOnceAndNextDepartureStartsAgain() throws Exception {
		prepareRecentAuthentication(3);
		long before = android.os.SystemClock.elapsedRealtime();
		SecurityStore.foreground(context, false);
		long first = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).getLong("at"));
		assertTrue(first >= before); assertTrue(first <= android.os.SystemClock.elapsedRealtime());
		String original = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString());
		SecurityStore.foreground(context, false); // onPause fallback after onStop must not extend it.
		assertEquals(original, SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString()));
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
		assertEquals(original, SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString()));
		long nextDeparture = android.os.SystemClock.elapsedRealtime();
		SecurityStore.foreground(context, false);
		long second = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).getLong("at"));
		assertTrue(second >= nextDeparture); assertTrue(second >= first);
		SecurityStore.foreground(context, true); assertFalse(SecurityStore.locked(context));
	}
	@Test public void failedBackgroundReceiptWriteRemainsLockedAfterProcessEntry() throws Exception {
		prepareRecentAuthentication(3);
		File blocker = new File(SecurityStore.directory(context), "state.json.new");
		assertTrue(blocker.mkdir()); assertTrue(new File(blocker, "background-write-fixture").createNewFile());
		try { SecurityStore.foreground(context, false); fail("Failed background write must be reported"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.locked(context));
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	private SecuritySessionProvider freshAuthority() {
		SecuritySessionProvider authority = new SecuritySessionProvider();
		android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
		info.authority = context.getPackageName() + ".test.background-session";
		authority.attachInfo(context, info); return authority;
	}
	private android.os.Bundle clientRequest() {
		android.os.Bundle request = new android.os.Bundle(); request.putBinder("client", new android.os.Binder());
		return request;
	}
	@Test public void lastClientDepartureIssuesOneGrantAndDuplicateInactiveDoesNotSlideIt() {
		SecuritySessionProvider authority = freshAuthority();
		String key = SecurityStore.directory(context).getAbsolutePath();
		android.os.Bundle first = clientRequest(), second = clientRequest();
		first.putLong("generation", authority.call("active", key, first).getLong("generation"));
		first.putInt("minutes", 3); first.putLong("authenticated_at", android.os.SystemClock.elapsedRealtime());
		assertTrue(authority.call("authenticated", key, first).getBoolean("unlocked"));
		authority.call("active", key, second);
		assertFalse(authority.call("inactive", key, first).containsKey("background_at"));
		android.os.Bundle background = authority.call("inactive", key, second);
		assertTrue(background.containsKey("background_at")); assertFalse(background.getBoolean("unlocked"));
		second.putLong("generation", background.getLong("generation"));
		second.putLong("authenticated_at", background.getLong("background_at"));
		assertTrue(authority.call("background-grant", key, second).getBoolean("background_grant"));
		assertFalse(authority.call("inactive", key, second).containsKey("background_at"));
		assertTrue(authority.call("background-grant", key, second).getBoolean("background_grant"));
		authority.call("active", key, second);
		assertFalse(authority.call("background-grant", key, second).getBoolean("background_grant"));
		assertTrue(authority.call("restore", key, second).getBoolean("unlocked"));
		assertTrue(authority.call("inactive", key, second).containsKey("background_at"));
	}
	@Test public void duplicateScreenOffKeepsPendingGrantButManualLockRevokesIt() {
		SecuritySessionProvider authority = freshAuthority();
		String key = SecurityStore.directory(context).getAbsolutePath();
		android.os.Bundle request = clientRequest();
		request.putLong("generation", authority.call("active", key, request).getLong("generation"));
		request.putInt("minutes", 3); request.putLong("authenticated_at", android.os.SystemClock.elapsedRealtime());
		authority.call("authenticated", key, request);
		android.os.Bundle background = authority.call("suspend", key, request);
		assertTrue(background.containsKey("background_at"));
		request.putLong("generation", background.getLong("generation"));
		request.putLong("authenticated_at", background.getLong("background_at"));
		assertFalse(authority.call("suspend", key, request).containsKey("background_at"));
		assertTrue(authority.call("background-grant", key, request).getBoolean("background_grant"));
		authority.call("lock", key, request);
		assertFalse(authority.call("background-grant", key, request).getBoolean("background_grant"));
	}
	@Test public void processRestartRestoresButIntervalChangeAndPendingResetRevokeRememberedAuthentication() throws Exception {
		prepareRecentAuthentication(3);
		String original = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString());
		SecurityStore.processStarted(context, true);
		assertTrue(SecurityStore.locked(context)); // Background startup must not open the profile.
		SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
		assertEquals(original, SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString()));
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		SecurityStore.update(context, s -> { s.put(ReauthenticationPolicy.KEY, 5); return null; });
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		SecurityStore.update(context, s -> { s.put("reset_pending", true); return null; });
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void repeatedColdEntryUsesTheOriginalReceiptWithoutRefreshingIt() throws Exception {
		prepareRecentAuthentication(5);
		String original = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString());
		for (int i = 0; i < 3; i++) {
			SecurityStore.processStarted(context, true);
			assertTrue(SecurityStore.locked(context));
			SecurityStore.foreground(context, true);
			assertFalse(SecurityStore.locked(context));
			assertEquals(original, SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString()));
		}
	}
	@Test public void verifiedReceiptCanRestoreAFreshAuthorityWithoutOldRamState() throws Exception {
		prepareRecentAuthentication(5);
		RememberedAuthentication receipt = SecurityStore.update(context, s -> RememberedAuthentication.read(context, s));
		assertNotNull(receipt);
		// A new provider object has no sessions, owners, generations or old client Binder.
		SecuritySessionProvider authority = new SecuritySessionProvider();
		android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
		info.authority = context.getPackageName() + ".test.fresh-session";
		authority.attachInfo(context, info);
		String key = SecurityStore.directory(context).getAbsolutePath();
		android.os.Bundle request = new android.os.Bundle(); request.putBinder("client", new android.os.Binder());
		assertFalse(authority.call("query", key, request).getBoolean("unlocked"));
		android.os.Bundle active = authority.call("active", key, request);
		request.putLong("generation", active.getLong("generation"));
		request.putInt("minutes", receipt.minutes); request.putLong("authenticated_at", receipt.authenticatedAt);
		assertTrue(authority.call("restore", key, request).getBoolean("unlocked"));
		authority.call("lock", key, request);
		request.putLong("generation", authority.call("query", key, request).getLong("generation"));
		assertFalse(authority.call("restore", key, request).getBoolean("unlocked"));
	}
	@Test public void forgedOrMalformedReceiptCannotRestoreEvenWithALiveRamGrant() throws Exception {
		for (String field : new String[]{"at", "boot", "minutes", "version", "tag"}) {
			prepareRecentAuthentication(5);
			SecurityStore.update(context, s -> {
				org.json.JSONObject receipt = s.getJSONObject(RememberedAuthentication.KEY);
				if (field.equals("tag")) receipt.put(field, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
				else receipt.put(field, receipt.getLong(field) + 1);
				return null;
			});
			SecurityStore.foreground(context, false);
			SecurityStore.foreground(context, true);
			assertTrue("Tampered " + field, SecurityStore.locked(context));
		}
		prepareRecentAuthentication(5);
		SecurityStore.update(context, s -> { s.getJSONObject(RememberedAuthentication.KEY).put("at", "123"); return null; });
		SecurityStore.foreground(context, false); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void explicitLockRevokesTheIndependentKeyEvenIfAnOldReceiptIsReplayed() throws Exception {
		prepareRecentAuthentication(5);
		org.json.JSONObject receipt = SecurityStore.update(context, s -> new org.json.JSONObject(s.getJSONObject(RememberedAuthentication.KEY).toString()));
		SecurityStore.lock(context);
		SecurityStore.update(context, s -> { s.put(RememberedAuthentication.KEY, receipt); return null; });
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
		// A new proof creates a different key; it cannot authenticate the old receipt either.
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		SecurityStore.update(context, s -> { s.put(RememberedAuthentication.KEY, receipt); return null; });
		SecurityStore.foreground(context, false); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void failedWriteCannotReviveTheOldDiskReceiptOnNextProcessEntry() throws Exception {
		prepareRecentAuthentication(5);
		String original = SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString());
		File blocker = new File(SecurityStore.directory(context), "state.json.new");
		assertTrue(blocker.mkdir()); assertTrue(new File(blocker, "cannot-replace").createNewFile());
		try { SecurityStore.update(context, s -> { s.put("write-failure-fixture", true); return null; }); fail("Write must fail"); }
		catch (IOException expected) {}
		// The old record may remain perfectly readable, but its key must already be invalid.
		assertEquals(original, SecurityStore.update(context, s -> s.getJSONObject(RememberedAuthentication.KEY).toString()));
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void failedManualLockCommitStillRevokesTheOldReceipt() throws Exception {
		prepareRecentAuthentication(5);
		File blocker = new File(SecurityStore.directory(context), "state.json.new");
		assertTrue(blocker.mkdir()); assertTrue(new File(blocker, "cannot-replace").createNewFile());
		try { SecurityStore.lock(context); fail("Receipt removal must report failed commit"); }
		catch (IOException expected) {}
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void profileChangeAndCredentialChangeInvalidateTheReceipt() throws Exception {
		prepareRecentAuthentication(5);
		SecurityStore.update(context, s -> { s.put("profile_generation", s.optLong("profile_generation") + 1); return null; });
		SecurityStore.foreground(context, true); assertTrue(SecurityStore.locked(context));
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		SecurityStore.setPin(context, "654321".toCharArray(), true);
		SecurityStore.foreground(context, true); assertTrue(SecurityStore.locked(context));
	}
	@Test public void freshSettingsProofCanCommitIntervalBeforeItsLeaseIsRevoked() throws Exception {
		prepareRecentAuthentication(5);
		long lease = SecuritySessionProvider.generation(context);
		SecurityStore.authorizedUpdate(context, () -> lease == SecuritySessionProvider.generation(context)
				&& SecuritySessionProvider.isUnlocked(context), s -> { s.put(ReauthenticationPolicy.KEY, 10); return null; });
		assertEquals(lease + 1, SecuritySessionProvider.generation(context));
		assertTrue(SecurityStore.locked(context));
		assertFalse(SecurityStore.update(context, s -> s.has(RememberedAuthentication.KEY)));
		SecurityStore.authenticatedUnlock(context, lease + 1);
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
	}
	@Test public void lostKeystoreKeyCannotAuthorizeAfterProcessEntryButPinStillWorks() throws Exception {
		prepareRecentAuthentication(5);
		RememberedAuthentication.revoke(context);
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), true));
		SecurityStore.authenticatedUnlock(context, SecuritySessionProvider.generation(context));
		SecurityStore.processStarted(context, true); SecurityStore.foreground(context, true);
		assertFalse(SecurityStore.locked(context));
	}
	@Test public void defaultImmediateAndStaleAuthenticationDoNotGainAGracePeriod() throws Exception {
		prepareRecentAuthentication(0);
		long old = SecuritySessionProvider.generation(context);
		SecurityStore.foreground(context, false);
		SecurityStore.foreground(context, true);
		assertTrue(SecurityStore.locked(context));
		try { SecurityStore.authenticatedUnlock(context, old); fail("Stale authentication"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.locked(context));
	}

	@Test public void expiredAuthorizationCannotCommitAChangedCredentialOrSetting() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		try { SecurityStore.setPin(context, "654321".toCharArray(), true, () -> false); fail("Expired authorization"); }
		catch (IOException expected) {}
		assertFalse(SecurityStore.update(context, s -> s.has("additional")));
		try { SecurityStore.authorizedUpdate(context, () -> false, s -> { s.put("delete_downloads_uri", "tree-test"); return null; }); fail("Expired consent"); }
		catch (IOException expected) {}
		assertFalse(SecurityStore.update(context, s -> s.has("delete_downloads_uri")));
	}

	@Test public void oldProfileGenerationCannotWriteDuringOrAfterReset() throws Exception {
		long old = ProfileGeneration.current(context);
		assertTrue(ProfileGeneration.canWrite(context, old));
		SecurityStore.update(context, s -> { s.put("reset_pending", true).put("profile_generation", old + 1); return null; });
		assertFalse(ProfileGeneration.canWrite(context, old));
		assertFalse(ProfileGeneration.canWrite(context, old + 1));
		SecurityStore.update(context, s -> { s.remove("reset_pending"); return null; });
		assertFalse(ProfileGeneration.canWrite(context, old));
		assertTrue(ProfileGeneration.canWrite(context, old + 1));
	}

	@Test public void staleAuthenticationLeaseCannotUnlockAfterRevocation() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		long lease = SecuritySessionProvider.generation(context);
		SecurityStore.lock(context);
		try { SecurityStore.unlock(context, lease); fail("Stale verification must not unlock"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.locked(context));
		SecurityStore.unlock(context, SecuritySessionProvider.generation(context));
		assertFalse(SecurityStore.locked(context));
	}
	private void prepareEmptyReceipt(long until) throws Exception {
		int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
		assertTrue(boot >= 0);
		SecurityStore.update(context, s -> {
			s.put("primary", new org.json.JSONObject()).put("profile_generation", 4)
					.put("empty_launch", "synthetic-one-shot").put("empty_launch_generation", 4)
					.put("empty_launch_boot", boot).put("empty_launch_until", until); return null;
		});
	}
	@Test public void emptyProfileReceiptUnlocksOnceAndCannotBeReplayed() throws Exception {
		prepareEmptyReceipt(android.os.SystemClock.elapsedRealtime() + 60_000);
		assertTrue(SecurityStore.consumeEmptyProfileToken(context, "synthetic-one-shot", SecuritySessionProvider.generation(context)));
		assertFalse(SecurityStore.locked(context));
		SecurityStore.lock(context);
		assertFalse(SecurityStore.consumeEmptyProfileToken(context, "synthetic-one-shot", SecuritySessionProvider.generation(context)));
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void revokedSessionCannotUseAnOtherwiseValidEmptyProfileReceipt() throws Exception {
		prepareEmptyReceipt(android.os.SystemClock.elapsedRealtime() + 60_000);
		long lease = SecuritySessionProvider.generation(context); SecurityStore.lock(context);
		try { SecurityStore.consumeEmptyProfileToken(context, "synthetic-one-shot", lease); fail("Revoked receipt lease"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.locked(context));
	}
	@Test public void expiredEmptyProfileReceiptDoesNotUnlockOrExtendItsDeadline() throws Exception {
		long expired = android.os.SystemClock.elapsedRealtime() - 1;
		prepareEmptyReceipt(expired);
		assertFalse(SecurityStore.consumeEmptyProfileToken(context, "synthetic-one-shot", SecuritySessionProvider.generation(context)));
		assertTrue(SecurityStore.locked(context));
		assertEquals(expired, (long) SecurityStore.update(context, s -> s.getLong("empty_launch_until")));
	}

	@Test public void wallClockCannotExtendOrCancelTheMonotonicRetryDelay() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
		assertTrue(boot >= 0);
		SecurityStore.update(context, s -> { s.put("failures", 5).put("retry_boot", boot)
				.put("retry_elapsed", android.os.SystemClock.elapsedRealtime() - 1)
				.put("retry_wall", System.currentTimeMillis() + 86_400_000L); return null; });
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), false));
		SecurityStore.update(context, s -> { s.put("failures", 5).put("retry_boot", boot)
				.put("retry_elapsed", android.os.SystemClock.elapsedRealtime() + 30_000)
				.put("retry_wall", System.currentTimeMillis() - 86_400_000L); return null; });
		assertEquals(SecurityStore.Result.WAIT, SecurityStore.verify(context, "123456".toCharArray(), false));
	}

	@Test public void newBootReanchorsRetryOnceAndWithinTheBoundedDelay() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
		assertTrue(boot >= 0);
		SecurityStore.update(context, s -> { s.put("failures", 5).put("retry_boot", boot - 1).put("retry_elapsed", Long.MAX_VALUE); return null; });
		long before = android.os.SystemClock.elapsedRealtime();
		assertEquals(SecurityStore.Result.WAIT, SecurityStore.verify(context, "123456".toCharArray(), false));
		long deadline = SecurityStore.update(context, s -> { assertEquals(boot, s.getInt("retry_boot")); return s.getLong("retry_elapsed"); });
		assertTrue(deadline >= before && deadline <= android.os.SystemClock.elapsedRealtime() + 30_000);
		assertEquals(SecurityStore.Result.WAIT, SecurityStore.verify(context, "123456".toCharArray(), false));
		assertEquals(deadline, (long) SecurityStore.update(context, s -> s.getLong("retry_elapsed")));
	}

	@Test public void primaryAndAdditionalDifferAndOnlyUnlockFlowAcceptsAdditional() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		try { SecurityStore.setPin(context, "123456".toCharArray(), true); fail("Same PIN must be rejected"); }
		catch (IOException expected) {}
		SecurityStore.setPin(context, "654321".toCharArray(), true);
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), false));
		assertEquals(SecurityStore.Result.ADDITIONAL, SecurityStore.verify(context, "654321".toCharArray(), false));
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "654321".toCharArray(), true));
		assertFalse(SecurityStore.pending(context)); // Validation itself never deletes anything.
	}

	@Test public void startupBackgroundAndInterruptedCleanupNeverUnlockTheProfile() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.unlock(context); assertFalse(SecurityStore.locked(context));
		SecurityStore.foreground(context, false); assertTrue(SecurityStore.locked(context));
		SecurityStore.foreground(context, true);
		SecurityStore.unlock(context); SecurityStore.processStarted(context, true); assertTrue(SecurityStore.locked(context));
		SecurityStore.update(context, s -> { s.put("reset_pending", true); return null; });
		SecurityStore.unlock(context); assertTrue(SecurityStore.locked(context));
		SecurityStore.processStarted(context, true); assertTrue(SecurityStore.pending(context));
		assertEquals(SecurityStore.Result.WAIT, SecurityStore.verify(context, "123456".toCharArray(), false));
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), true));
		assertTrue(SecurityStore.locked(context)); // Recovery verification alone does not bypass cleanup.
	}

	@Test public void fifthFailedAttemptPersistsDelayAcrossProcessInitialization() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		for (int i = 0; i < 5; i++) assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "999999".toCharArray(), false));
		SecurityStore.processStarted(context, true);
		assertEquals(SecurityStore.Result.WAIT, SecurityStore.verify(context, "123456".toCharArray(), false));
	}

	@Test public void applyingQueuedPreferencesRevokesOldFolderConsentBeforeTheFolderChanges() throws Exception {
		SecurityStore.update(context, s -> { s.put("delete_downloads_uri", "tree-A").put("delete_downloads_label", "fixture-A"); return null; });
		File current = new File(sandbox, "preferences.xml"), pending = new File(sandbox, "preferences.restore.xml");
		byte[] restored = "<map><string name=\"download_uri_tree\">tree-B</string></map>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		try (FileOutputStream output = new FileOutputStream(pending)) { output.write(restored); }
		assertTrue(PreferencesRestore.applyPending(context, pending, current));
		assertArrayEquals(restored, java.nio.file.Files.readAllBytes(current.toPath()));
		assertFalse(pending.exists());
		assertFalse(SecurityStore.update(context, s -> s.has("delete_downloads_uri") || s.has("delete_downloads_label")));
		assertFalse(SecurityStore.pending(context));
	}

	@Test public void failedConsentCommitLeavesQueuedAndCurrentPreferencesUntouched() throws Exception {
		SecurityStore.update(context, s -> { s.put("delete_downloads_uri", "tree-A"); return null; });
		File current = new File(sandbox, "preferences.xml"), pending = new File(sandbox, "preferences.restore.xml");
		byte[] original = "old-settings".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		try (FileOutputStream output = new FileOutputStream(current)) { output.write(original); }
		try (FileOutputStream output = new FileOutputStream(pending)) { output.write(new byte[]{1, 2, 3}); }
		File blocker = new File(SecurityStore.directory(context), "state.json.new"); assertTrue(blocker.mkdir());
		assertTrue(new File(blocker, "block-write").createNewFile());
		try { PreferencesRestore.applyPending(context, pending, current); fail("Restore must fail closed"); }
		catch (IOException expected) {}
		assertArrayEquals(original, java.nio.file.Files.readAllBytes(current.toPath()));
		assertArrayEquals(new byte[]{1, 2, 3}, java.nio.file.Files.readAllBytes(pending.toPath()));
	}

	@Test public void noPendingBackupDoesNotRevokeConsentForAnUnavailableApprovedFolder() throws Exception {
		SecurityStore.update(context, s -> { s.put("delete_downloads_uri", "tree-A"); return null; });
		assertFalse(PreferencesRestore.applyPending(context, new File(sandbox, "absent.restore.xml"), new File(sandbox, "preferences.xml")));
		assertEquals("tree-A", SecurityStore.update(context, s -> s.getString("delete_downloads_uri")));
		assertEquals("tree-A", PrivacyResetPolicy.approvedDownloadTarget("tree-A", null));
	}

	@Test public void recoveredReadWithoutAResetReturnsNormalStartupWithoutAnyReceipt() throws Exception {
		SecurityStore.update(context, s -> { s.put("fixture", "must-survive"); return null; });
		File mutex = new File(SecurityStore.directory(context), "state.lock");
		try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(mutex, "rw");
				java.nio.channels.FileLock lock = file.getChannel().lock()) {
			assertTrue(lock.isValid());
			assertTrue(PrivacyReset.requiresRecovery(context));
			try { PrivacyReset.recoveryState(context); fail("A locked record must not be considered readable"); }
			catch (IOException expected) {}
		}
		assertEquals(PrivacyReset.RecoveryState.NORMAL_START, PrivacyReset.recoveryState(context));
		assertNull(PrivacyReset.launchToken(context));
		assertEquals("must-survive", SecurityStore.update(context, s -> s.getString("fixture")));
		assertFalse(SecurityStore.update(context, s -> s.has("empty_launch") || s.optBoolean("reset_pending")));
	}

	@Test public void pendingCleanupCannotUseTheNormalRecoveryExit() throws Exception {
		SecurityStore.update(context, s -> { s.put("reset_pending", true); return null; });
		assertEquals(PrivacyReset.RecoveryState.RESET_PENDING, PrivacyReset.recoveryState(context));
		try { PrivacyReset.launchToken(context); fail("No normal exit while pending"); } catch (IOException expected) {}
		try { SecurityStore.restoreSettingsSession(context, SecuritySessionProvider.generation(context)); fail("Pending reset must not authorize settings"); }
		catch (IOException expected) {}
	}

	@Test public void freshPrimaryVerificationCanReopenTheSettingsSessionAfterBackground() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.setPin(context, "654321".toCharArray(), true);
		SecurityStore.unlock(context);
		SecurityStore.foreground(context, false); assertTrue(SecurityStore.locked(context));
		SecurityStore.foreground(context, true);
		long lease = SecuritySessionProvider.generation(context);
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "654321".toCharArray(), true));
		assertTrue(SecurityStore.locked(context));
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), true));
		assertTrue(SecurityStore.locked(context)); // Verification alone must remain insufficient.
		SecurityStore.restoreSettingsSession(context, lease);
		assertTrue(SecuritySessionProvider.isUnlocked(context));
		SecurityStore.authorizedUpdate(context, () -> lease == SecuritySessionProvider.generation(context)
				&& SecuritySessionProvider.isUnlocked(context), s -> { s.put("fixture-setting", true); return null; });
		assertTrue(SecurityStore.update(context, s -> s.optBoolean("fixture-setting")));
	}

	@Test public void revokedFreshVerificationCannotRestoreTheSettingsSession() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		long lease = SecuritySessionProvider.generation(context);
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), true));
		SecurityStore.lock(context);
		try { SecurityStore.restoreSettingsSession(context, lease); fail("Stale verification accepted"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.locked(context));
	}

	@Test public void captureOptInRequiresARealSessionAndDoesNotDisableTheLock() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.unlock(context);
		assertTrue(SecurityStore.screenCaptureBlocked(context));
		SecurityStore.setScreenshotsAllowed(context, true, () -> SecuritySessionProvider.isUnlocked(context));
		assertFalse(SecurityStore.screenCaptureBlocked(context));
		assertTrue(AppSecurity.isProtected(context));
		SecurityStore.lock(context);
		assertTrue(SecurityStore.screenCaptureBlocked(context));
		assertTrue(SecurityStore.locked(context));
		SecurityStore.unlock(context);
		SecurityStore.setScreenshotsAllowed(context, false, () -> SecuritySessionProvider.isUnlocked(context));
		assertTrue(SecurityStore.screenCaptureBlocked(context));
	}

	@Test public void expiredCaptureApprovalAndPendingResetCannotEnableCapture() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		try { SecurityStore.setScreenshotsAllowed(context, true, () -> false); fail("Stale approval accepted"); }
		catch (IOException expected) {}
		assertFalse(SecurityStore.update(context, s -> s.optBoolean(SecurityStore.ALLOW_SCREENSHOTS)));
		SecurityStore.update(context, s -> { s.put("reset_pending", true); return null; });
		try { SecurityStore.setScreenshotsAllowed(context, true, () -> true); fail("Reset cannot allow capture"); }
		catch (IOException expected) {}
		assertTrue(SecurityStore.screenCaptureBlocked(context));
	}

	@Test public void unreadableStateBlocksCaptureEvenWithPreviouslyStoredOptIn() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.unlock(context);
		SecurityStore.setScreenshotsAllowed(context, true, () -> true);
		try (FileOutputStream output = new FileOutputStream(new File(SecurityStore.directory(context), "state.json"))) {
			output.write(new byte[]{'{', 'x'});
		}
		assertTrue(SecurityStore.screenCaptureBlocked(context));
		assertFalse(SecurityStore.designCaptureAllowed(context));
		assertFalse(SecuritySessionProvider.isUnlocked(context));
	}

	@Test public void temporaryCapturePermissionDoesNotUnlockOrCancelPendingRecovery() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		assertFalse(SecurityStore.designCaptureAllowed(context));
		SecurityStore.unlock(context);
		SecurityStore.setScreenshotsAllowed(context, true, () -> SecuritySessionProvider.isUnlocked(context));
		SecurityStore.lock(context);
		boolean designRelease = ScreenCapturePolicy.designRelease(com.mishiranu.dashchan.BuildConfig.VERSION_NAME);
		assertEquals(designRelease, SecurityStore.designCaptureAllowed(context));
		assertTrue(SecurityStore.locked(context));
		assertFalse(SecuritySessionProvider.isUnlocked(context));
		assertTrue(SecurityStore.screenCaptureBlocked(context));
		SecurityStore.update(context, s -> { s.put("reset_pending", true); return null; });
		assertEquals(designRelease, SecurityStore.designCaptureAllowed(context));
		assertTrue(SecurityStore.pending(context));
		assertTrue(SecurityStore.locked(context));
		assertTrue(SecurityStore.screenCaptureBlocked(context));
	}

	@Test public void rejectedOversizedWriteLeavesThePreviousFileReadableAndUnchanged() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		android.util.AtomicFile file = new android.util.AtomicFile(new File(SecurityStore.directory(context), "state.json"));
		byte[] before = file.readFully(); char[] large = new char[SecurityStateCodec.MAX_BYTES]; java.util.Arrays.fill(large, 'x');
		try { SecurityStore.update(context, s -> { s.put("fixture-large", new String(large)); return null; }); fail("Oversized write accepted"); }
		catch (IOException expected) {}
		assertArrayEquals(before, file.readFully());
		assertTrue(SecurityStore.enabled(context));
		assertFalse(SecurityStore.update(context, s -> s.has("fixture-large")));
	}

	@Test public void oversizedLegacyJournalMigratesButStillRequiresTheSamePrimaryPin() throws Exception {
		SecurityStore.setPin(context, "123456".toCharArray(), false);
		SecurityStore.setPin(context, "654321".toCharArray(), true);
		org.json.JSONObject old = SecurityStore.update(context, s -> new org.json.JSONObject(s.toString()));
		String primary = old.getJSONObject("primary").toString(), additional = old.getJSONObject("additional").toString();
		org.json.JSONArray targets = new org.json.JSONArray();
		char[] chars = new char[7300]; java.util.Arrays.fill(chars, 'x');
		for (int i = 0; i < 12; i++) targets.put("content://fixture/" + new String(chars) + i);
		old.put("external_cleanup_targets", targets).put("external_cleanup_incomplete", true)
				.put("reset_pending", true).put("reset_local_done", true).put("reset_tree", "tree-fixture");
		byte[] legacy = old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(legacy.length > SecurityStateCodec.MAX_BYTES);
		try (FileOutputStream out = new FileOutputStream(new File(SecurityStore.directory(context), "state.json"))) { out.write(legacy); }
		assertTrue(SecurityStore.enabled(context)); assertTrue(SecurityStore.pending(context));
		org.json.JSONObject migrated = SecurityStore.update(context, s -> new org.json.JSONObject(s.toString()));
		assertEquals(primary, migrated.getJSONObject("primary").toString());
		assertEquals(additional, migrated.getJSONObject("additional").toString());
		assertEquals("tree-fixture", migrated.getString("reset_tree")); assertTrue(migrated.getBoolean("reset_local_done"));
		assertEquals(12, migrated.getJSONArray("external_cleanup_target_hashes").length());
		assertTrue(new File(SecurityStore.directory(context), "state.json").length() <= SecurityStateCodec.MAX_BYTES);
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "000000".toCharArray(), true));
		assertEquals(SecurityStore.Result.FAILED, SecurityStore.verify(context, "654321".toCharArray(), true));
		assertEquals(SecurityStore.Result.PRIMARY, SecurityStore.verify(context, "123456".toCharArray(), true));
		assertTrue(SecurityStore.locked(context)); assertTrue(SecurityStore.pending(context));
	}

	@Test public void failedLegacyMigrationPreservesOriginalJournalAndRemainsLocked() throws Exception {
		org.json.JSONObject legacy = new org.json.JSONObject().put("primary", new org.json.JSONObject())
				.put("reset_pending", true).put("reset_tree", "tree-fixture").put("external_cleanup_incomplete", true);
		char[] chars = new char[7300]; java.util.Arrays.fill(chars, 'x');
		org.json.JSONArray targets = new org.json.JSONArray();
		for (int i = 0; i < 12; i++) targets.put("content://fixture/" + new String(chars) + i);
		legacy.put("external_cleanup_targets", targets);
		byte[] original = legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		SecurityStore.update(context, s -> null);
		File state = new File(SecurityStore.directory(context), "state.json");
		try (FileOutputStream out = new FileOutputStream(state)) { out.write(original); }
		File blocker = new File(SecurityStore.directory(context), "state.json.new");
		assertTrue(blocker.mkdir()); assertTrue(new File(blocker, "fixture-blocker").createNewFile());
		try { SecurityStore.enabled(context); fail("Failed migration must not allow normal startup"); }
		catch (IOException expected) {}
		assertArrayEquals(original, java.nio.file.Files.readAllBytes(state.toPath()));
		assertTrue(SecurityStore.locked(context));
	}

	@Test public void corruptRecordFailsClosedRatherThanDisablingLock() throws Exception {
		assertFalse(SecurityStore.enabled(context));
		try (FileOutputStream output = new FileOutputStream(new File(SecurityStore.directory(context), "state.json"))) {
			output.write(new byte[]{'{', 'x'});
		}
		assertTrue(SecurityStore.locked(context));
		try { SecurityStore.enabled(context); fail("Corruption must not return disabled"); }
		catch (IOException expected) {}
	}
}
