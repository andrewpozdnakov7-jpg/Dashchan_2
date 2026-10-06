package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Source guards, not a simulation of WebView or the system package installer. */
public class AuditPassOneContractTest {
	private static String source(String relative) throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			File file = new File(root, relative);
			if (file.isFile()) return Files.readString(file.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find source: " + relative);
	}

	private static String appSource(String relative) throws Exception {
		return source("src/com/mishiranu/dashchan/" + relative);
	}

	@Test public void networkModeKeepsSerializedBackupValueWithoutRadioGenerations() throws Exception {
		String observer = appSource("content/NetworkObserver.java");
		assertTrue(observer.contains("networkState == NetworkState.WIFI || networkState == NetworkState.MOBILE"));
		assertFalse(observer.contains("getActiveNetworkInfo"));
		assertFalse(observer.contains("getDataNetworkType"));
		assertFalse(observer.contains("NETWORK_TYPE_"));
		assertTrue(appSource("content/Preferences.java").contains("WIFI_MOBILE(\"wifi_3g\""));
		assertTrue(appSource("content/async/PreloadMediaTask.java").contains("isWifiOrMobileConnected()"));
	}

	@Test public void cookieLoadWaitsForCompletionAndRejectsStaleOwners() throws Exception {
		assertTrue(appSource("util/WebViewUtils.java").contains("removeAllCookies(removed ->"));
		String service = appSource("content/service/webview/WebViewService.java");
		assertTrue(service.contains("WebViewUtils.clearAll(clearedView, () ->"));
		assertTrue(service.contains("this.cookieRequest != cookieRequest"));
		assertTrue(service.contains("webView != clearedView"));
		assertTrue(service.contains("!cookieRequest.ready"));
	}

	@Test public void decoderWaitsForDecodedImageAndOwnsTemporaryBitmap() throws Exception {
		String decoder = appSource("media/WebViewDecoder.java");
		assertFalse(decoder.contains("setPictureListener"));
		assertTrue(decoder.contains("i.decode().then(report,report)"));
		assertTrue(decoder.contains("postVisualStateCallback"));
		assertTrue(decoder.contains("view.draw(new Canvas(pendingBitmap))"));
		assertTrue(decoder.contains("pendingBitmap.recycle()"));
		assertTrue(decoder.contains("HANDLER.postDelayed(decoder.timeout, 20000)"));
	}

	@Test public void sessionWritesOffMainThreadAndExplicitlyRequiresConfirmation() throws Exception {
		String session = appSource("content/UpdateInstallSession.java");
		assertTrue(session.contains("ConcurrentUtils.PARALLEL_EXECUTOR.execute("));
		assertTrue(session.contains("session.fsync(output)"));
		assertTrue(session.contains("USER_ACTION_REQUIRED"));
		assertTrue(session.contains("new Intent(context, StatusReceiver.class)"));
		assertTrue(session.contains("PendingIntent.FLAG_MUTABLE"));
		assertTrue(session.contains("PackageInstaller.EXTRA_SESSION_ID"));
		assertTrue(session.contains("operation.equals(token(context))"));
		assertFalse(session.contains("context.startActivity("));
		assertFalse(session.contains("getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE"));
	}

	@Test public void installationDoesNotUseHiddenErrorRetryOrActivityResultAsSuccess() throws Exception {
		String updater = appSource("content/UpdaterActivity.java");
		assertFalse(updater.contains("ACTION_INSTALL_PACKAGE"));
		assertFalse(updater.contains("INSTALL_FAILED_INVALID_APK"));
		assertFalse(updater.contains("result.getResultCode()"));
		assertTrue(updater.contains("canRequestPackageInstalls()"));
		assertTrue(updater.contains("STATUS_FAILURE_ABORTED"));
		assertTrue(updater.contains("BuildConfig.ALLOW_APPLICATION_SELF_UPDATE"));
		assertTrue(updater.contains("BuildConfig.REQUIRE_EXTENSION_INSTALL_CONSENT"));
	}

	@Test public void oldActivityCannotCancelAnotherModelsPreparation() throws Exception {
		String session = appSource("content/UpdateInstallSession.java");
		assertTrue(session.contains("ownedToken.equals(token(context)) && status(context) == PREPARING"));
		assertTrue(session.contains("if (preparing) return;"));
		assertTrue(session.contains("!info.isSealed()"));
		assertTrue(session.contains("status(context) >= PackageInstaller.STATUS_SUCCESS"));
	}

	@Test public void statusReceiverIsPrivateAndUninstallSupportsExternallyInstalledExtensions() throws Exception {
		String manifest = source("AndroidManifest.xml");
		assertTrue(manifest.contains(".content.UpdateInstallSession$StatusReceiver\"\n\t\t\tandroid:exported=\"false\""));
		assertTrue(appSource("ui/preference/ChanFragment.java").contains("Intent.ACTION_DELETE"));
	}
}
