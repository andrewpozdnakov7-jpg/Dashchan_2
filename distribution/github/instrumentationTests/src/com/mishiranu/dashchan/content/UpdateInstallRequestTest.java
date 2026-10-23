package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.junit.Test;

/** Prepared, not run. Private synthetic preferences only; never creates/commits an install session. */
public class UpdateInstallRequestTest {
	private static final class Fixture extends ContextWrapper {
		private final String prefix = "test-update-request-" + UUID.randomUUID() + "-";
		private final Set<String> stores = new HashSet<>();
		Fixture() { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
		@Override public Context getApplicationContext() { return this; }
		@Override public SharedPreferences getSharedPreferences(String name, int mode) {
			stores.add(name); return getBaseContext().getSharedPreferences(prefix + name, mode);
		}
		void seed(String token, int status, int index, String... files) {
			assertTrue(getSharedPreferences("update_install_session", MODE_PRIVATE).edit().clear()
					.putString("token", token).putString("files", new JSONArray(Arrays.asList(files)).toString())
					.putInt("status", status).putInt("index", index).putInt("session", -1).commit());
		}
		void dispose() {
			for (String name : stores) assertTrue(getBaseContext().getSharedPreferences(prefix + name, MODE_PRIVATE).edit().clear().commit());
		}
	}
	private void fixture(java.util.function.Consumer<Fixture> work) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture fixture = new Fixture();
			try { work.accept(fixture); } finally { fixture.dispose(); }
		});
	}
	private ArrayList<String> queue(String... names) { return new ArrayList<>(Arrays.asList(names)); }
	@Test public void oldSuccessfulSessionDoesNotReplaceOrAdvanceNewApkRequest() {
		fixture(context -> {
			context.seed("fixture-old-success", PackageInstaller.STATUS_SUCCESS, 0, "client-old.apk");
			ArrayList<String> requested = queue("client-new.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, null, requested);
			assertEquals(UpdateInstallSession.RequestKind.FRESH, result.kind);
			assertNull(UpdateInstallSession.token(context)); assertEquals(0, result.index);
			assertNull(result.files); assertEquals(queue("client-new.apk"), requested);
		});
	}
	@Test public void terminalSuccessWithIdenticalFilenamesIsStillNotOwnershipOfFreshRequest() {
		fixture(context -> {
			context.seed("fixture-old-same-name", PackageInstaller.STATUS_SUCCESS, 0, "client-same.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, null, queue("client-same.apk"));
			assertEquals(UpdateInstallSession.RequestKind.FRESH, result.kind); assertNull(UpdateInstallSession.token(context));
		});
	}
	@Test public void oldFailureIsNotReportedAsFailureOfANewRequest() {
		fixture(context -> {
			context.seed("fixture-old-failure", PackageInstaller.STATUS_FAILURE_ABORTED, 1, "extension-old.apk", "client-old.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, null, queue("client-new.apk"));
			assertEquals(UpdateInstallSession.RequestKind.FRESH, result.kind); assertEquals(0, result.index);
			assertNull(UpdateInstallSession.token(context));
		});
	}
	@Test public void ownedTerminalResultStillAdvancesItsOwnMultiPackageQueueInCaller() {
		fixture(context -> {
			context.seed("fixture-owned", PackageInstaller.STATUS_SUCCESS, 1, "extension.apk", "client-current.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, "fixture-owned", null, queue("client-new.apk"));
			assertEquals(UpdateInstallSession.RequestKind.RESUME, result.kind);
			assertEquals("fixture-owned", result.token); assertEquals(1, result.index);
			assertEquals(queue("extension.apk", "client-current.apk"), result.files);
			assertEquals(PackageInstaller.STATUS_SUCCESS, result.status); assertEquals("fixture-owned", UpdateInstallSession.token(context));
		});
	}
	@Test public void clearingOldResultDoesNotClearLaterOwnedPreparationOrCompletion() {
		fixture(context -> {
			context.seed("fixture-old", PackageInstaller.STATUS_SUCCESS, 0, "client-old.apk");
			assertEquals(UpdateInstallSession.RequestKind.FRESH,
					UpdateInstallSession.resolveRequest(context, null, null, queue("client-new.apk")).kind);
			context.seed("fixture-new", UpdateInstallSession.PREPARING, 0, "client-new.apk");
			assertEquals(UpdateInstallSession.RequestKind.RESUME,
					UpdateInstallSession.resolveRequest(context, "fixture-new", null, queue("client-new.apk")).kind);
			context.seed("fixture-new", PackageInstaller.STATUS_SUCCESS, 0, "client-new.apk");
			assertEquals(UpdateInstallSession.RequestKind.RESUME,
					UpdateInstallSession.resolveRequest(context, "fixture-new", null, queue("client-new.apk")).kind);
			assertEquals("fixture-new", UpdateInstallSession.token(context));
		});
	}
	@Test public void explicitResumeKeepsAuthoritativeQueueAndIndex() {
		fixture(context -> {
			context.seed("fixture-resume", PackageInstaller.STATUS_PENDING_USER_ACTION, 1, "extension.apk", "client.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, "fixture-resume", queue("different.apk"));
			assertEquals(UpdateInstallSession.RequestKind.RESUME, result.kind); assertEquals(1, result.index);
			assertEquals(queue("extension.apk", "client.apk"), result.files);
			assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, result.status);
		});
	}
	@Test public void liveUnrelatedQueueIsNeitherReplacedNorAbandoned() {
		fixture(context -> {
			for (int status : new int[]{UpdateInstallSession.PREPARING, UpdateInstallSession.COMMITTED, PackageInstaller.STATUS_PENDING_USER_ACTION}) {
				context.seed("fixture-live", status, 1, "extension.apk", "client-current.apk");
				UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, null, queue("client-new.apk"));
				assertEquals(UpdateInstallSession.RequestKind.BUSY, result.kind);
				assertEquals("fixture-live", UpdateInstallSession.token(context)); assertEquals(status, UpdateInstallSession.status(context));
				assertEquals(1, UpdateInstallSession.index(context)); assertEquals(queue("extension.apk", "client-current.apk"), UpdateInstallSession.files(context));
			}
		});
	}
	@Test public void sameLiveQueueCanBeRecoveredWithoutSavedActivityOwnership() {
		fixture(context -> {
			context.seed("fixture-recover", UpdateInstallSession.COMMITTED, 0, "client.apk");
			assertEquals(UpdateInstallSession.RequestKind.RESUME,
					UpdateInstallSession.resolveRequest(context, null, null, queue("client.apk")).kind);
			assertEquals("fixture-recover", UpdateInstallSession.token(context));
		});
	}
	@Test public void obsoleteNotificationCannotResumeOrClearANewerSession() {
		fixture(context -> {
			context.seed("fixture-current", PackageInstaller.STATUS_SUCCESS, 0, "client-current.apk");
			UpdateInstallSession.RequestState result = UpdateInstallSession.resolveRequest(context, null, "fixture-obsolete", queue("client-obsolete.apk"));
			assertEquals(UpdateInstallSession.RequestKind.STALE, result.kind); assertEquals("fixture-current", UpdateInstallSession.token(context));
		});
	}
}
