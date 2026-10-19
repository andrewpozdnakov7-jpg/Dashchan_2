package com.mishiranu.dashchan.content.update;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanManager;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.UpdaterActivity;
import com.mishiranu.dashchan.content.FileProvider;
import com.mishiranu.dashchan.content.async.ReadUpdateTask;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Isolated preference names and synthetic bytes only; never invokes PackageInstaller. */
public class DownloadedUpdateTest {
	private Context context;
	private SharedPreferences preferences;
	@Before public void before() {
		Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String name = "test-downloaded-update-" + UUID.randomUUID();
		preferences = target.getSharedPreferences(name, Context.MODE_PRIVATE);
		context = new ContextWrapper(target) {
			@Override public Context getApplicationContext() { return this; }
			@Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return preferences; }
		};
	}
	@After public void after() { assertTrue(preferences.edit().clear().commit()); }
	private UpdaterActivity.Request request(String version, byte[] bytes) throws Exception {
		return new UpdaterActivity.Request("client", version, Uri.parse("https://example.invalid/update.apk"),
				MessageDigest.getInstance("SHA-256").digest(bytes),
				new ChanManager.Fingerprints(Collections.singleton(String.join("", Collections.nCopies(64, "a")))),
				(long) BuildConfig.VERSION_CODE + 10, bytes.length);
	}
	private void seedLaunch(long launch, String previous) throws Exception {
		JSONObject state = new JSONObject(preferences.getString("receipt", "{}"));
		assertTrue(preferences.edit().putString("receipt", state.put("launches", launch).put("last_launch", previous).toString()).commit());
	}
	@Test public void receiptAndPostponementSurviveNewStoreReads() throws Exception {
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test", new byte[]{1, 2, 3}));
		assertEquals(entry.id, DownloadedUpdate.read(context).id);
		assertTrue(DownloadedUpdate.claim(context, entry.id));
		assertFalse(DownloadedUpdate.claim(context, entry.id));
		assertTrue(DownloadedUpdate.postpone(context, entry.id));
		assertEquals(3, DownloadedUpdate.read(context).state.getLong("next_launch"));
		assertEquals(1, DownloadedUpdate.read(context).state.getInt("postponements"));
		assertFalse(DownloadedUpdate.due(DownloadedUpdate.read(context)));
		// A newly restored Activity in this same process must not increment the persisted launch counter.
		DownloadedUpdate.launch(context); DownloadedUpdate.launch(context);
		assertEquals(0, DownloadedUpdate.read(context).state.getLong("launches"));
	}
	@Test public void newProcessesReachExactlyThreeThenSixThenFifteen() throws Exception {
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test", new byte[]{1}));
		assertTrue(DownloadedUpdate.postpone(context, entry.id));
		long count = 0;
		int[] intervals = {3, 6, 15, 15};
		for (int interval : intervals) {
			for (int i = 1; i <= interval; i++) {
				seedLaunch(count, "previous-process-" + count);
				DownloadedUpdate.launch(context); count++;
				// offered belongs to the old process; emulate a new process receipt after relaunch.
				JSONObject state = DownloadedUpdate.read(context).state.put("offered", "previous-process");
				assertTrue(preferences.edit().putString("receipt", state.toString()).commit());
				assertEquals(i == interval, DownloadedUpdate.due(DownloadedUpdate.read(context)));
			}
			assertTrue(DownloadedUpdate.postpone(context, entry.id));
		}
	}
	@Test public void sameMetadataKeepsScheduleButAnotherArtifactResetsIt() throws Exception {
		UpdaterActivity.Request request = request("test", new byte[]{1});
		DownloadedUpdate.Entry first = DownloadedUpdate.remember(context, request);
		DownloadedUpdate.postpone(context, first.id);
		DownloadedUpdate.Entry same = DownloadedUpdate.remember(context, request);
		assertEquals(first.id, same.id);
		assertEquals(3, same.state.getLong("next_launch"));
		DownloadedUpdate.Entry changed = DownloadedUpdate.remember(context, request("test", new byte[]{2}));
		assertNotEquals(first.id, changed.id);
		assertEquals(0, changed.state.getLong("postponements"));
		assertEquals(0, changed.state.getLong("next_launch"));
		assertTrue(DownloadedUpdate.due(changed));
		DownloadedUpdate.clear(context, first.id);
		assertEquals(changed.id, DownloadedUpdate.read(context).id);
		assertFalse(DownloadedUpdate.beginInstallation(context, first.id));
	}
	@Test public void installationCancellationAndRecoveryDoNotAdvanceTwiceInOneLaunch() throws Exception {
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test", new byte[]{1}));
		assertTrue(DownloadedUpdate.beginInstallation(context, entry.id));
		assertTrue(DownloadedUpdate.beginInstallation(context, entry.id));
		assertEquals(3, DownloadedUpdate.read(context).state.getLong("next_launch"));
		assertEquals(1, DownloadedUpdate.read(context).state.getInt("postponements"));
	}
	@Test public void malformedAndTraversalReceiptsCannotBeUsed() throws Exception {
		DownloadedUpdate.remember(context, request("test", new byte[]{1}));
		JSONObject state = DownloadedUpdate.read(context).state.put("file", "../client-test.apk");
		assertTrue(preferences.edit().putString("receipt", state.toString()).commit());
		assertNull(DownloadedUpdate.read(context));
		assertTrue(preferences.edit().putString("receipt", "not-json").commit());
		assertNull(DownloadedUpdate.read(context));
		assertTrue(preferences.edit().putInt("receipt", 1).commit());
		assertNull(DownloadedUpdate.read(context));
	}
	@Test public void sessionCopyChecksTheActualBytesAndLength() throws Exception {
		byte[] bytes = {1, 2, 3};
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test", bytes));
		File file = File.createTempFile("update-copy-test-", ".bin", context.getCacheDir());
		try {
			try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
			ByteArrayOutputStream copied = new ByteArrayOutputStream();
			try (FileInputStream input = new FileInputStream(file)) { DownloadedUpdate.copyVerified(entry, input, copied); }
			assertArrayEquals(bytes, copied.toByteArray());
			for (byte[] invalid : new byte[][]{{1, 2}, {1, 2, 4}, {1, 2, 3, 4}}) {
				try (FileOutputStream output = new FileOutputStream(file)) { output.write(invalid); }
				try (FileInputStream input = new FileInputStream(file)) {
					try { DownloadedUpdate.copyVerified(entry, input, new ByteArrayOutputStream()); fail("Changed APK accepted"); }
					catch (IOException expected) { /* No commit may follow a failed copy. */ }
				}
			}
		} finally { assertTrue(file.delete()); }
	}
	@Test public void missingDamagedAndNonApkFilesAreNotReady() throws Exception {
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test-" + UUID.randomUUID(), new byte[]{1, 2, 3}));
		File file = FileProvider.getUpdatesFile(entry.file);
		assertNotNull(file);
		assertFalse(file.exists());
		assertEquals(DownloadedUpdate.Check.MISSING, DownloadedUpdate.check(context, entry));
		assertTrue(file.createNewFile());
		try {
			assertEquals(DownloadedUpdate.Check.INVALID, DownloadedUpdate.check(context, entry));
			try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1, 2, 4}); }
			assertEquals(DownloadedUpdate.Check.INVALID, DownloadedUpdate.check(context, entry));
			try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{1, 2, 3}); }
			assertEquals(DownloadedUpdate.Check.INVALID, DownloadedUpdate.check(context, entry));
		} finally { assertTrue(file.delete()); }
	}
	@Test public void alreadyInstalledVersionDoesNotRequireOrOfferAnApk() throws Exception {
		UpdaterActivity.Request higher = request("test", new byte[]{1});
		UpdaterActivity.Request installed = new UpdaterActivity.Request("client", higher.versionName, higher.uri,
				higher.sha256sum, higher.checkFingerprints, BuildConfig.VERSION_CODE, higher.length);
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, installed);
		assertEquals(DownloadedUpdate.Check.INSTALLED, DownloadedUpdate.check(context, entry));
		DownloadedUpdate.clear(context, entry.id);
		assertNull(DownloadedUpdate.read(context));
	}
	@Test public void failedDurableWriteDoesNotAuthorizeAnInstallationOrAcknowledgement() throws Exception {
		SharedPreferences failing = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
				SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
			if (!method.getName().equals("edit")) return method.invoke(preferences, args);
			SharedPreferences.Editor editor = preferences.edit();
			return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
					new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
				if (editMethod.getName().equals("commit")) return false;
				Object result = editMethod.invoke(editor, editArgs);
				return result == editor ? editProxy : result;
			});
		});
		Context blocked = new ContextWrapper(context) {
			@Override public Context getApplicationContext() { return this; }
			@Override public SharedPreferences getSharedPreferences(String name, int mode) { return failing; }
		};
		try { DownloadedUpdate.remember(blocked, request("test", new byte[]{1})); fail("Failed receipt write accepted"); }
		catch (IOException expected) { /* No download or installation may start. */ }
		assertNull(DownloadedUpdate.read(context));
		DownloadedUpdate.Entry entry = DownloadedUpdate.remember(context, request("test", new byte[]{1}));
		try { DownloadedUpdate.claim(blocked, entry.id); fail("Failed offer write accepted"); }
		catch (IOException expected) { /* Do not display an unrecorded automatic offer. */ }
		assertTrue(DownloadedUpdate.due(DownloadedUpdate.read(context)));
		try { DownloadedUpdate.beginInstallation(blocked, entry.id); fail("Failed postponement write accepted"); }
		catch (IOException expected) { /* Do not enter installation with lost reminder state. */ }
		assertEquals(0, DownloadedUpdate.read(context).state.getInt("postponements"));
	}
	@Test public void currentLegacyManifestChecksumReachesTheDownloadRequest() throws Exception {
		Class<?> versionType = Class.forName(ReadUpdateTask.class.getName() + "$DataVersion");
		Object legacy = null;
		for (Object value : versionType.getEnumConstants()) if (((Enum<?>) value).name().equals("LEGACY")) legacy = value;
		assertNotNull(legacy);
		java.lang.reflect.Method extract = ReadUpdateTask.class.getDeclaredMethod("extractPackageItem", versionType,
				JSONObject.class, String.class, String.class, Uri.class, Long.class, ChanManager.Fingerprints.class);
		extract.setAccessible(true);
		String digest = String.join("", Collections.nCopies(64, "a"));
		JSONObject json = new JSONObject().put("title", "Release").put("name", "test")
				.put("code", (long) BuildConfig.VERSION_CODE + 10).put("minVersion", 1).put("maxVersion", 1)
				.put("minSdk", 30).put("length", 3).put("source", "https://example.invalid/update.apk")
				.put("fingerprint", digest).put("sha256sum", digest);
		ReadUpdateTask.PackageItem item = (ReadUpdateTask.PackageItem) extract.invoke(null, legacy, json, "client", null,
				Uri.parse("https://example.invalid/data.json"), Long.valueOf(BuildConfig.VERSION_CODE), null);
		assertNotNull(item);
		assertNotNull(item.sha256sum);
		assertEquals(32, item.sha256sum.length);
		for (byte value : item.sha256sum) assertEquals((byte) 0xaa, value);
		UpdaterActivity.Request request = new UpdaterActivity.Request("client", item.versionName, item.source,
				item.sha256sum, item.fingerprints, item.versionCode, item.length);
		assertEquals(item.versionCode, DownloadedUpdate.remember(context, request).code);
	}
}
