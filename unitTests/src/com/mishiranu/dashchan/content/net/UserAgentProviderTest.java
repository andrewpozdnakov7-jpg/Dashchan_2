package com.mishiranu.dashchan.content.net;

import org.junit.Assert;
import org.junit.Test;

public class UserAgentProviderTest {
	private static final String PREFIX = "Mozilla/5.0 (Linux; Android 10; K; wv) " +
			"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/";
	private static final String SUFFIX = ".0.0.0 Mobile Safari/537.36";

	@Test
	public void sanitizesDeviceAndFirmwareData() {
		String source = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/AP3A.260715.001; wv) " +
				"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
				"Chrome/150.0.6724.102 Mobile Safari/537.36";
		String result = UserAgentProvider.sanitizeUserAgent(source);

		Assert.assertEquals(PREFIX + "150" + SUFFIX, result);
		Assert.assertFalse(result.contains("Pixel 9 Pro"));
		Assert.assertFalse(result.contains("AP3A.260715.001"));
		Assert.assertFalse(result.contains("150.0.6724.102"));
	}

	@Test
	public void keepsOnlyChromiumMajor() {
		Assert.assertEquals(PREFIX + "150" + SUFFIX,
				UserAgentProvider.sanitizeUserAgent("Chrome/150.0.6724.102"));
	}

	@Test
	public void producesSameResultForDifferentDevices() {
		String first = UserAgentProvider.sanitizeUserAgent(
				"Mozilla/5.0 (Linux; Android 14; Phone One Build/A.1; wv) Chrome/149.1.2.3");
		String second = UserAgentProvider.sanitizeUserAgent(
				"Mozilla/5.0 (Linux; Android 16; Phone Two Build/B.9; wv) Chrome/149.9.8.7");

		Assert.assertEquals(first, second);
		Assert.assertEquals(PREFIX + "149" + SUFFIX, first);
	}

	@Test
	public void handlesUserAgentWithoutBuildToken() {
		Assert.assertEquals(PREFIX + "148" + SUFFIX,
				UserAgentProvider.sanitizeUserAgent(
						"Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/537.36 Chrome/148.2.3.4"));
	}

	@Test
	public void usesPrivacySafeFallbackForUnexpectedInput() {
		String expected = PREFIX + "100" + SUFFIX;
		Assert.assertEquals(expected, UserAgentProvider.sanitizeUserAgent(null));
		Assert.assertEquals(expected, UserAgentProvider.sanitizeUserAgent("Mozilla/5.0"));
		Assert.assertEquals(expected, UserAgentProvider.sanitizeUserAgent("Chrome/not-a-version"));
	}

	@Test
	public void acceptsChromiumProductName() {
		Assert.assertEquals(PREFIX + "147" + SUFFIX,
				UserAgentProvider.sanitizeUserAgent("Chromium/147.12.34.56"));
	}
}
