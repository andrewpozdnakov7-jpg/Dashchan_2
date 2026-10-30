package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Source-set boundary assertions; the release APK audit additionally verifies compiled artifacts. */
public class StartupNoticeDistributionTest {
	@Test public void compiledResetRetainsTheSharedNoticeCounters() throws Exception {
		try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(
				"com/mishiranu/dashchan/content/security/PrivacyReset.class")) {
			assertNotNull("Compiled reset class must be inspected", input);
			String bytecode = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
			assertEquals(com.mishiranu.dashchan.BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION,
					bytecode.contains("material3-acknowledgements"));
			assertEquals(com.mishiranu.dashchan.BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION,
					bytecode.contains("material3-default-design-acknowledgements-v1"));
		}
	}
	@Test public void bothDistributionsUseTheSameMaterialDialogAndResources() throws Exception {
		Path shared = Path.of("distribution/material3");
		assertTrue(Files.isRegularFile(shared.resolve("src/com/mishiranu/dashchan/ui/StartupNotices.java")));
		assertTrue(Files.isRegularFile(shared.resolve("src/com/mishiranu/dashchan/ui/StartupNoticePolicy.java")));
		for (String locale : new String[] {"values", "values-ru"}) {
			assertTrue(Files.isRegularFile(shared.resolve("res/" + locale + "/startup_notice.xml")));
		}
		for (String flavor : new String[] {"github", "fdroid"}) {
			assertFalse(Files.exists(Path.of("distribution/" + flavor + "/src/com/mishiranu/dashchan/ui/StartupNotices.java")));
		}
		String gradle = Files.readString(Path.of("build.gradle"));
		assertTrue(gradle.contains("['github', 'fdroid'].each"));
		assertTrue(gradle.contains(".java.srcDir 'distribution/material3/src'"));
		assertTrue(gradle.contains(".res.srcDir 'distribution/material3/res'"));
	}
	@Test public void durableAcknowledgementOccursOnlyInTheExplicitButtonHandler() throws Exception {
		String text = Files.readString(Path.of("distribution/material3/src/com/mishiranu/dashchan/ui/StartupNotices.java"));
		assertEquals(1, text.split("\\.commit\\(\\)", -1).length - 1);
		assertTrue(text.indexOf(".commit()") > text.indexOf("setOnClickListener"));
		assertTrue(text.contains("editor.commitChecked()"));
		assertTrue(text.contains("BUTTON_NEGATIVE).setOnClickListener"));
		assertTrue(text.contains("host.recreate()"));
		assertFalse(text.contains("versionCode"));
		assertFalse(text.contains("isNewInterfaceMotionEnabled"));
		assertTrue(text.contains("setCanceledOnTouchOutside(false)"));
		assertTrue(text.contains("setCancelable(false)"));
		assertTrue(text.contains("decor.hasWindowFocus()"));
		assertTrue(text.contains("manager.isStateSaved()"));
	}
}
