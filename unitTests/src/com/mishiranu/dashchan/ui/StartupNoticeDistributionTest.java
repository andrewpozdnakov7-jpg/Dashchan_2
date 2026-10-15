package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Source-set boundary assertions; the release APK audit additionally verifies compiled artifacts. */
public class StartupNoticeDistributionTest {
	@Test public void compiledResetContainsTheNoticeCounterOnlyForTheGithubFlavor() throws Exception {
		try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(
				"com/mishiranu/dashchan/content/security/PrivacyReset.class")) {
			assertNotNull("Compiled reset class must be inspected", input);
			String bytecode = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
			assertEquals(com.mishiranu.dashchan.BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION,
					bytecode.contains("material3-acknowledgements"));
		}
	}
	@Test public void fdroidBridgeHasNoDialogCounterOrResources() throws Exception {
		String bridge = Files.readString(Path.of("distribution/fdroid/src/com/mishiranu/dashchan/ui/StartupNotices.java"));
		assertTrue(bridge.contains("BooleanSupplier ready) {}"));
		for (String forbidden : new String[] {"DialogFragment", "SharedPreferences", "MaterialAlertDialogBuilder", "material3_notice"}) {
			assertFalse(forbidden, bridge.contains(forbidden));
		}
		assertFalse(Files.exists(Path.of("distribution/fdroid/res/values/startup_notice.xml")));
		assertFalse(Files.exists(Path.of("distribution/fdroid/src/com/mishiranu/dashchan/ui/StartupNoticePolicy.java")));
	}
	@Test public void durableAcknowledgementOccursOnlyInTheExplicitButtonHandler() throws Exception {
		String text = Files.readString(Path.of("distribution/github/src/com/mishiranu/dashchan/ui/StartupNotices.java"));
		assertEquals(1, text.split("\\.commit\\(\\)", -1).length - 1);
		assertTrue(text.indexOf(".commit()") > text.indexOf("setOnClickListener"));
		assertFalse(text.contains("versionCode"));
		assertFalse(text.contains("isNewInterfaceMotionEnabled"));
		assertTrue(text.contains("setCanceledOnTouchOutside(false)"));
		assertTrue(text.contains("setCancelable(false)"));
		assertTrue(text.contains("decor.hasWindowFocus()"));
		assertTrue(text.contains("manager.isStateSaved()"));
	}
}
