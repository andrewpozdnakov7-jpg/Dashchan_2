package com.mishiranu.dashchan.content.update;

import static org.junit.Assert.*;
import android.os.Build;
import com.mishiranu.dashchan.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Offline fixtures for the published manifest shape and the startup false-update regression. */
public class UpdateCheckRegressionTest {
	private static JSONObject client(int code, String name, int minSdk) throws Exception {
		return new JSONObject().put("code", code).put("name", name).put("minSdk", minSdk)
				.put("source", "https://example.invalid/client.apk");
	}

	private static UpdateResult legacy(int installedCode, JSONObject... clients) throws Exception {
		JSONArray array = new JSONArray();
		for (JSONObject client : clients) array.put(client);
		return UpdateManifest.parse(new JSONObject().put("client", array).toString(),
				BuildConfig.APPLICATION_ID, installedCode);
	}

	@Test public void publishedShapeDoesNotOfferTheInstalledOrAnOlderBuild() throws Exception {
		assertEquals(UpdateResult.Status.NO_UPDATE, legacy(500, client(500, "3.2.72", 1)).status);
		assertEquals(UpdateResult.Status.NO_UPDATE, legacy(501, client(500, "3.2.72", 1)).status);
	}

	@Test public void newerCodeWinsEvenWhenVersionNameHasNotChanged() throws Exception {
		UpdateResult result = legacy(500, client(501, "3.2.72", 1));
		assertEquals(UpdateResult.Status.UPDATE_AVAILABLE, result.status);
		assertEquals(501, result.versionCode);
		assertTrue(UpdateChecker.shouldShowAutomatically(result, 500, 0));
		assertFalse(UpdateChecker.shouldShowAutomatically(result, 501, 0));
		assertFalse(UpdateChecker.shouldShowAutomatically(result, 500, 501));
	}

	@Test public void clientOrderAndIncompatibleVariantsDoNotHideACompatibleUpdate() throws Exception {
		UpdateResult result = legacy(500, client(501, "compatible", 1),
				client(503, "future Android", Build.VERSION.SDK_INT + 1), client(502, "newest compatible", 1));
		assertEquals(502, result.versionCode);
		assertEquals(UpdateResult.Status.UPDATE_AVAILABLE, result.status);
	}

	@Test public void unsupportedMinSdkIsReportedWithoutClaimingInstallability() throws Exception {
		UpdateResult result = legacy(500, client(501, "future Android", Build.VERSION.SDK_INT + 1));
		assertEquals(UpdateResult.Status.UPDATE_UNAVAILABLE, result.status);
		assertEquals(Build.VERSION.SDK_INT + 1, result.minSdk);
	}

	@Test public void schemaOneAndPackageIsolationArePreserved() throws Exception {
		JSONObject app = new JSONObject().put("packageName", BuildConfig.APPLICATION_ID)
				.put("versionCode", 501).put("versionName", "new");
		String json = new JSONObject().put("schemaVersion", 1).put("app", app).toString();
		assertEquals(UpdateResult.Status.UPDATE_AVAILABLE,
				UpdateManifest.parse(json, BuildConfig.APPLICATION_ID, 500).status);
		assertEquals(UpdateResult.Status.NO_UPDATE, UpdateManifest.parse(json, "other.package", 500).status);
	}

	@Test public void githubTagMatchingInstalledVersionDoesNotOfferItAgain() throws Exception {
		for (String tag : new String[] {BuildConfig.VERSION_NAME, " v" + BuildConfig.VERSION_NAME + " "}) {
			JSONObject release = new JSONObject().put("tag_name", tag).put("name", "Slooop " + tag)
					.put("body", "**Slooop " + tag + "**");
			assertEquals(UpdateResult.Status.NO_UPDATE, GitHubReleaseFallback.resultFromRelease(release).status);
		}
	}

	@Test public void unverifiedReleaseStaysManualAndCannotSuppressTheNextKnownCode() throws Exception {
		UpdateResult result = GitHubReleaseFallback.resultFromRelease(new JSONObject()
				.put("tag_name", "unverified-release").put("name", "New release"));
		assertEquals(UpdateResult.Status.RELEASE_FOUND, result.status);
		assertFalse(UpdateChecker.shouldShowAutomatically(result, BuildConfig.VERSION_CODE, 0));
		UpdateResult next = UpdateResult.available(UpdateResult.Source.PRIMARY_JSON,
				BuildConfig.VERSION_CODE + 1, "next", 0, null, null, null, null, false);
		assertTrue(UpdateChecker.shouldShowAutomatically(next, BuildConfig.VERSION_CODE, -1));
	}

	@Test public void verifiedCodeTakesPriorityOverAnIdenticalTag() throws Exception {
		JSONObject release = new JSONObject().put("tag_name", BuildConfig.VERSION_NAME)
				.put("body", "version code: " + (BuildConfig.VERSION_CODE + 1));
		assertEquals(UpdateResult.Status.UPDATE_AVAILABLE, GitHubReleaseFallback.resultFromRelease(release).status);
	}
}
