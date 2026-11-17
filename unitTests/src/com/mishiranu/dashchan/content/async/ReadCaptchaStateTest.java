package com.mishiranu.dashchan.content.async;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadCaptchaStateTest {
	@Test public void restoresKnownStatesAndMissingState() {
		for (ReadCaptchaTask.CaptchaState state : ReadCaptchaTask.CaptchaState.values()) {
			assertEquals(state, ReadCaptchaTask.CaptchaState.fromSaved(state.name()));
		}
		assertNull(ReadCaptchaTask.CaptchaState.fromSaved(null));
	}

	@Test public void unavailableSavedModeRequiresManualCaptchaLoading() {
		assertEquals(ReadCaptchaTask.CaptchaState.MAY_LOAD, ReadCaptchaTask.CaptchaState.fromSaved("retired_mode"));
		assertEquals(ReadCaptchaTask.CaptchaState.MAY_LOAD, ReadCaptchaTask.CaptchaState.fromSaved("future_mode"));
	}
}
