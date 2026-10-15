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
