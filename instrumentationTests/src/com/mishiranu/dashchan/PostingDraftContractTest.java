package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.text.JsonSerial;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Model round trips only: no network, sending, preference edits or explicit storage writes. */
@RunWith(AndroidJUnit4.class)
public class PostingDraftContractTest {
	private static DraftsStorage.AttachmentDraft attachment(int id, int flags) {
		return new DraftsStorage.AttachmentDraft(String.format(java.util.Locale.US, "%064x", id),
				"fixture-" + id + ".png", "rating-" + id, (flags & 1) != 0, (flags & 2) != 0,
				(flags & 4) != 0, (flags & 8) != 0, new GraphicsUtils.Reencoding("png", 87, 3));
	}

	private static void sameAttachment(DraftsStorage.AttachmentDraft expected, DraftsStorage.AttachmentDraft actual) {
		assertNotNull(actual);
		assertEquals(expected.hash, actual.hash);
		assertEquals(expected.name, actual.name);
		assertEquals(expected.rating, actual.rating);
		assertEquals(expected.optionUniqueHash, actual.optionUniqueHash);
		assertEquals(expected.optionRemoveMetadata, actual.optionRemoveMetadata);
		assertEquals(expected.optionRemoveFileName, actual.optionRemoveFileName);
		assertEquals(expected.optionSpoiler, actual.optionSpoiler);
		if (expected.reencoding == null) {
			assertNull(actual.reencoding);
		} else {
			assertNotNull(actual.reencoding);
			assertEquals(expected.reencoding.format, actual.reencoding.format);
			assertEquals(expected.reencoding.quality, actual.reencoding.quality);
			assertEquals(expected.reencoding.reduce, actual.reencoding.reduce);
		}
	}

	private static DraftsStorage.AttachmentDraft roundTrip(DraftsStorage.AttachmentDraft draft) throws Exception {
		try (JsonSerial.Writer writer = JsonSerial.writer()) {
			draft.serialize(writer);
			try (JsonSerial.Reader reader = JsonSerial.reader(writer.build())) {
				return DraftsStorage.AttachmentDraft.deserialize(reader);
			}
		}
	}

	private static DraftsStorage.PostDraft roundTrip(DraftsStorage.PostDraft draft) throws Exception {
		try (JsonSerial.Writer writer = JsonSerial.writer()) {
			draft.serialize(writer);
			try (JsonSerial.Reader reader = JsonSerial.reader(writer.build())) {
				return DraftsStorage.PostDraft.deserialize(reader);
			}
		}
	}

	@Test public void attachmentRoundTripPreservesEveryOptionCombination() throws Exception {
		for (int flags = 0; flags < 16; flags++) {
			DraftsStorage.AttachmentDraft draft = attachment(flags + 1, flags);
			sameAttachment(draft, roundTrip(draft));
		}
	}

	@Test public void attachmentRoundTripPreservesAbsentRatingAndReencoding() throws Exception {
		DraftsStorage.AttachmentDraft draft = new DraftsStorage.AttachmentDraft(attachment(1, 0).hash,
				null, null, false, false, false, false, null);
		sameAttachment(draft, roundTrip(draft));
	}

	@Test public void postRoundTripPreservesAllFieldsCursorAndOrderedAttachments() throws Exception {
		for (int flags = 0; flags < 8; flags++) {
			ArrayList<DraftsStorage.AttachmentDraft> attachments = new ArrayList<>(Arrays.asList(
					attachment(3, 3), attachment(1, 5), attachment(2, 10)));
			DraftsStorage.PostDraft draft = new DraftsStorage.PostDraft("fixture-chan", "test", "123",
					"fixture-name", "sage", "fixture-password", "Subject", "line one\nстрока два", 7,
					attachments, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, "fixture-icon");
			DraftsStorage.PostDraft restored = roundTrip(draft);
			assertEquals(draft.chanName, restored.chanName);
			assertEquals(draft.boardName, restored.boardName);
			assertEquals(draft.threadNumber, restored.threadNumber);
			assertEquals(draft.name, restored.name);
			assertEquals(draft.email, restored.email);
			assertEquals(draft.password, restored.password);
			assertEquals(draft.subject, restored.subject);
			assertEquals(draft.comment, restored.comment);
			assertEquals(draft.commentCarriage, restored.commentCarriage);
			assertEquals(draft.optionSage, restored.optionSage);
			assertEquals(draft.optionSpoiler, restored.optionSpoiler);
			assertEquals(draft.optionOriginalPoster, restored.optionOriginalPoster);
			assertEquals(draft.userIcon, restored.userIcon);
			assertEquals(attachments.size(), restored.attachmentDrafts.size());
			for (int i = 0; i < attachments.size(); i++) sameAttachment(attachments.get(i), restored.attachmentDrafts.get(i));
		}
	}

	@Test public void sparsePostKeepsCurrentDefaultsAndNewThreadIdentity() throws Exception {
		try (JsonSerial.Reader reader = JsonSerial.reader(
				"{\"chanName\":\"fixture-chan\",\"boardName\":\"test\",\"future\":{\"value\":1}}"
						.getBytes(StandardCharsets.UTF_8))) {
			DraftsStorage.PostDraft draft = DraftsStorage.PostDraft.deserialize(reader);
			assertEquals("fixture-chan", draft.chanName);
			assertEquals("test", draft.boardName);
			assertNull(draft.threadNumber);
			assertNull(draft.comment);
			assertNull(draft.attachmentDrafts);
			assertNull(draft.userIcon);
			assertEquals(0, draft.commentCarriage);
			assertFalse(draft.optionSage || draft.optionSpoiler || draft.optionOriginalPoster);
			assertTrue(draft.isEmpty());
		}
	}

	// Use the same Bundle key and a marshalled Parcel as process-state restoration.
	private static DraftsStorage.CaptchaDraft roundTrip(DraftsStorage.CaptchaDraft draft) {
		Parcel output = Parcel.obtain();
		Parcel input = Parcel.obtain();
		try {
			Bundle bundle = new Bundle();
			bundle.putParcelable("captchaDraft", draft);
			output.writeBundle(bundle);
			byte[] bytes = output.marshall();
			input.unmarshall(bytes, 0, bytes.length);
			input.setDataPosition(0);
			Bundle restored = input.readBundle(DraftsStorage.CaptchaDraft.class.getClassLoader());
			assertNotNull(restored);
			return AndroidUtils.getParcelable(restored, "captchaDraft", DraftsStorage.CaptchaDraft.class);
		} finally {
			input.recycle();
			output.recycle();
		}
	}

	@Test public void captchaBundlePreservesEveryStateInputValidityAndFlags() {
		for (ReadCaptchaTask.CaptchaState state : ReadCaptchaTask.CaptchaState.values()) {
			for (ChanConfiguration.Captcha.Input type : ChanConfiguration.Captcha.Input.values()) {
				for (ChanConfiguration.Captcha.Validity validity : ChanConfiguration.Captcha.Validity.values()) {
					for (int flags = 0; flags < 4; flags++) {
						ChanPerformer.CaptchaData data = new ChanPerformer.CaptchaData();
						data.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-challenge");
						data.put(ChanPerformer.CaptchaData.INPUT, "fixture-old-input");
						DraftsStorage.CaptchaDraft draft = new DraftsStorage.CaptchaDraft("fixture-type", state,
								data, "fixture-loaded-type", type, validity, "123abc", null,
								(flags & 1) != 0, (flags & 2) != 0, 123456789L, "test", "123");
						DraftsStorage.CaptchaDraft restored = roundTrip(draft);
						assertNotNull(restored);
						assertEquals(draft.captchaType, restored.captchaType);
						assertEquals(state, restored.captchaState);
						assertEquals("fixture-challenge", restored.captchaData.get(ChanPerformer.CaptchaData.CHALLENGE));
						assertEquals("fixture-old-input", restored.captchaData.get(ChanPerformer.CaptchaData.INPUT));
						assertEquals(draft.loadedCaptchaType, restored.loadedCaptchaType);
						assertEquals(type, restored.loadedInput);
						assertEquals(validity, restored.loadedValidity);
						assertEquals(draft.text, restored.text);
						assertNull(restored.image);
						assertEquals(draft.large, restored.large);
						assertEquals(draft.blackAndWhite, restored.blackAndWhite);
						assertEquals(draft.loadTime, restored.loadTime);
						assertEquals(draft.boardName, restored.boardName);
						assertEquals(draft.threadNumber, restored.threadNumber);
					}
				}
			}
		}
	}

	@Test public void captchaBundlePreservesNullStateAndNewThread() {
		DraftsStorage.CaptchaDraft draft = roundTrip(new DraftsStorage.CaptchaDraft(null, null, null, null,
				null, null, null, null, false, false, 0, "test", null));
		assertNotNull(draft);
		assertNull(draft.captchaType);
		assertNull(draft.captchaState);
		assertNull(draft.captchaData);
		assertNull(draft.loadedCaptchaType);
		assertNull(draft.loadedInput);
		assertNull(draft.loadedValidity);
		assertNull(draft.text);
		assertNull(draft.image);
		assertFalse(draft.large || draft.blackAndWhite);
		assertEquals(0, draft.loadTime);
		assertEquals("test", draft.boardName);
		assertNull(draft.threadNumber);
	}

	@Test public void captchaBitmapSurvivesMarshallingWithoutSharingTheOriginal() {
		Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		image.eraseColor(Color.BLUE);
		image.setPixel(1, 1, Color.RED);
		DraftsStorage.CaptchaDraft restored = null;
		try {
			restored = roundTrip(new DraftsStorage.CaptchaDraft("fixture", ReadCaptchaTask.CaptchaState.CAPTCHA,
					null, null, null, null, "text", image, false, true, 12, "test", "123"));
			assertNotNull(restored.image);
			assertNotSame(image, restored.image);
			assertEquals(2, restored.image.getWidth());
			assertEquals(2, restored.image.getHeight());
			assertEquals(Color.BLUE, restored.image.getPixel(0, 0));
			assertEquals(Color.RED, restored.image.getPixel(1, 1));
		} finally {
			image.recycle();
			if (restored != null && restored.image != null) restored.image.recycle();
		}
	}

	@Test public void sendCaptchaCopyDoesNotOverwriteTheLoadedChallengeInput() {
		ChanPerformer.CaptchaData loaded = new ChanPerformer.CaptchaData();
		loaded.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-challenge");
		loaded.put(ChanPerformer.CaptchaData.INPUT, "old-input");
		ChanPerformer.CaptchaData payload = loaded.copy();
		payload.put(ChanPerformer.CaptchaData.INPUT, "new-input");
		assertEquals("old-input", loaded.get(ChanPerformer.CaptchaData.INPUT));
		assertEquals("new-input", payload.get(ChanPerformer.CaptchaData.INPUT));
		assertEquals("fixture-challenge", payload.get(ChanPerformer.CaptchaData.CHALLENGE));
	}
}
