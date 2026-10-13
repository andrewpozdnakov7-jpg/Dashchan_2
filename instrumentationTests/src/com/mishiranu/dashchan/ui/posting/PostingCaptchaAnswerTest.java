package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PostingCaptchaAnswerTest {
	@Test public void savedSolutionsAreDistinctFromNoCaptchaAndAuthorization() {
		assertFalse(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.SKIP, null));
		ChanPerformer.CaptchaData data = new ChanPerformer.CaptchaData();
		assertFalse(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.SKIP, data));
		data.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-answer");
		assertTrue(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.SKIP, data));
		assertFalse(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.PASS, data));
		assertFalse(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.CAPTCHA, data));
		assertFalse(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.NEED_LOAD, data));
		data = new ChanPerformer.CaptchaData(); data.put(ReadCaptchaTask.RECAPTCHA_SKIP_RESPONSE, "fixture-answer");
		assertTrue(PostingCaptchaController.hasSavedAnswer(ReadCaptchaTask.CaptchaState.SKIP, data));
	}
}
