package com.mishiranu.dashchan.media.compression;

import static org.junit.Assert.*;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.util.Collections;
import org.json.JSONArray;
import org.junit.Test;

/** Pure formatting contracts; no recording, codec creation, networking or media payload processing. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public class CompressionDiagnosticsTest {
	@Test public void traceRetainsTimingAndDropsAllUnstructuredExtras() {
		JSONArray windows = new JSONArray();
		windows.put("1234us@56ms(file=/private/secret.mp4, url=https://private.invalid, csd=abcdef)");
		windows.put("EOS@78ms(account=private-account)");
		windows.put("unexpected raw content");
		assertEquals("[1234us@56ms,EOS@78ms,unavailable]", CompressionDiagnostics.times(windows, true));
		assertEquals("[unavailable]", CompressionDiagnostics.times(windows, false));
		assertEquals("[]", CompressionDiagnostics.times(null, true));
	}
	@Test public void traceNeverExportsComponentIdsOrUnknownStageEventLabels() {
		assertEquals("AssetLoader", CompressionDiagnostics.stage("AssetLoader[https://private.invalid/secret.mp4]"));
		assertEquals("Other", CompressionDiagnostics.stage("private-file.mp4"));
		assertEquals("Other", CompressionDiagnostics.event("private-account"));
		assertEquals("ProducedOutput", CompressionDiagnostics.event("ProducedOutput"));
		assertEquals("redacted", CompressionDiagnostics.token("/private/secret.mp4"));
		assertEquals("video/hevc", CompressionDiagnostics.mime("video/hevc"));
		assertEquals("unknown", CompressionDiagnostics.mime("file:///private/secret.mp4"));
	}
	@Test public void formatOmitsIdsLabelsLanguagesAndCodecInitializationBytes() {
		Format format = new Format.Builder().setId("private-id").setLabel("private-label").setLanguage("private-language")
				.setSampleMimeType("video/avc").setWidth(480).setHeight(728).setFrameRate(30)
				.setInitializationData(Collections.singletonList(new byte[] {0x12, 0x34, 0x56})).build();
		String published = CompressionDiagnostics.format(format);
		assertTrue(published.contains("mime=video/avc"));
		assertTrue(published.contains("width=480 height=728"));
		assertTrue(published.contains("init_blocks=1"));
		assertFalse(published.contains("private"));
		assertFalse(published.contains("123456"));
	}
}
