package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual controllers and Android models; fake persistence, no network, sending or real draft writes. */
@RunWith(AndroidJUnit4.class)
public class PostingStateControllersTest {
	private static class Store implements PostingDraftController.Store {
		DraftsStorage.PostDraft post, failed;
		DraftsStorage.CaptchaDraft captcha;
		boolean queued;
		int postWrites, captchaWrites, failedReads, consumed;
		final ArrayList<DraftsStorage.AttachmentDraft> future = new ArrayList<>();
		final ArrayList<String> events = new ArrayList<>();
		@Override public DraftsStorage.PostDraft getPostDraft(String chan, String board, String thread) { return post; }
		@Override public boolean isPostDraftQueued(DraftsStorage.PostDraft draft) { return queued; }
		@Override public DraftsStorage.PostDraft restoreFailedPostDraft(String chan, String board, String thread) {
			failedReads++; return failed;
		}
		@Override public DraftsStorage.CaptchaDraft getCaptchaDraft(String chan) { return captcha; }
		@Override public ArrayList<DraftsStorage.AttachmentDraft> getFutureAttachmentDrafts() { return future; }
		@Override public void consumeFutureAttachmentDrafts() { consumed++; future.clear(); }
		@Override public void storePost(DraftsStorage.PostDraft draft) { postWrites++; post = draft; events.add("post"); }
		@Override public void storeCaptcha(String chan, DraftsStorage.CaptchaDraft draft) {
			assertEquals("fixture-chan", chan); captchaWrites++; captcha = draft; events.add("captcha");
		}
	}
	private static class Host implements PostingCaptchaController.Host {
		int buttons, shown, inputs, errors, configurations;
		String text;
		ReadCaptchaTask.CaptchaState state;
		Bitmap image;
		@Override public void updateSendButtonState() { buttons++; }
		@Override public void showLoading() {}
		@Override public void showCaptcha(ReadCaptchaTask.CaptchaState state, ChanConfiguration.Captcha.Input input,
				Bitmap image, boolean large, boolean blackAndWhite) {
			shown++; this.state = state; this.image = image;
		}
		@Override public void setInput(String text) { inputs++; this.text = text; }
		@Override public void showError(ErrorItem errorItem) { errors++; }
		@Override public void updatePostingConfigurationIfNeeded() { configurations++; }
	}
	private static PostingCaptchaController controller(Host host, String board, String thread) {
		PostingCaptchaController controller = new PostingCaptchaController(() -> 987654321L);
		controller.configure("fixture-chan", board, thread, "fixture-type");
		controller.attachView(host);
		return controller;
	}
	private static DraftsStorage.CaptchaDraft captcha(ReadCaptchaTask.CaptchaState state,
			ChanConfiguration.Captcha.Validity validity, String board, String thread, Bitmap image) {
		ChanPerformer.CaptchaData data = new ChanPerformer.CaptchaData();
		data.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-challenge");
		data.put(ChanPerformer.CaptchaData.INPUT, "original-input");
		return new DraftsStorage.CaptchaDraft("fixture-type", state, data, null,
				ChanConfiguration.Captcha.Input.NUMERIC, validity, "12345", image, true, true, 123456789L, board, thread);
	}
	private static DraftsStorage.PostDraft post(PostingDraftController controller) {
		return controller.obtainPostDraft("fixture-chan", "test", "123", "name", "email", "fixture-password",
				"subject", "line one\nстрока два", 7, new ArrayList<>(), true, true, true, "icon");
	}
	private static DraftsStorage.CaptchaDraft roundTrip(DraftsStorage.CaptchaDraft draft) {
		Parcel output = Parcel.obtain(), input = Parcel.obtain();
		try {
			Bundle state = new Bundle();
			state.putParcelable("captcha", draft);
			output.writeBundle(state);
			byte[] bytes = output.marshall();
			input.unmarshall(bytes, 0, bytes.length);
			input.setDataPosition(0);
			Bundle restored = input.readBundle(DraftsStorage.CaptchaDraft.class.getClassLoader());
			assertNotNull(restored);
			return AndroidUtils.getParcelable(restored, "captcha", DraftsStorage.CaptchaDraft.class);
		} finally { input.recycle(); output.recycle(); }
	}

	@Test public void lifecycleSaveCapturesOnceBeforeStoreAndSkipsAfterSuccess() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		DraftsStorage.PostDraft post = post(controller);
		DraftsStorage.CaptchaDraft captcha = captcha(ReadCaptchaTask.CaptchaState.PASS, null, "test", "123", null);
		controller.saveDraft(false, "fixture-chan", () -> post, () -> captcha);
		controller.saveDraft(false, "fixture-chan", () -> { throw new AssertionError("duplicate post capture"); },
				() -> { throw new AssertionError("duplicate captcha capture"); });
		assertEquals(Arrays.asList("post", "captcha"), store.events);
		assertSame(post, store.post); assertSame(captcha, store.captcha);
		controller.resetSavedGuard();
		controller.saveDraft(true, "fixture-chan", () -> { throw new AssertionError("resurrected successful draft"); },
				() -> { throw new AssertionError("resurrected captcha"); });
		assertEquals(1, store.postWrites); assertEquals(1, store.captchaWrites);
	}

	@Test public void resumeAndRejectedSendResetGuardButQueuedSendKeepsIt() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		DraftsStorage.PostDraft post = post(controller);
		controller.storeForSend(post, "fixture-chan", () -> null);
		controller.saveDraft(false, "fixture-chan", () -> post, () -> null);
		assertEquals(1, store.postWrites);
		controller.resetSavedGuard();
		controller.saveDraft(false, "fixture-chan", () -> post, () -> null);
		assertEquals(2, store.postWrites); assertEquals(2, store.captchaWrites);
		assertEquals(Arrays.asList("post", "captcha", "post", "captcha"), store.events);
	}

	@Test public void immediateChangesDoNotConsumeLifecycleSaveGuard() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		DraftsStorage.PostDraft post = post(controller);
		controller.storePostDraft(post);
		controller.saveDraft(false, "fixture-chan", () -> post, () -> null);
		assertEquals(2, store.postWrites); assertEquals(1, store.captchaWrites);
	}

	@Test public void rawPostFieldsCursorFlagsAndAttachmentOrderStayIntact() {
		PostingDraftController controller = new PostingDraftController(new Store());
		ArrayList<DraftsStorage.AttachmentDraft> attachments = new ArrayList<>(Arrays.asList(
				new DraftsStorage.AttachmentDraft("hash-b", "b.png", "r", true, false, true, false, null),
				new DraftsStorage.AttachmentDraft("hash-a", "a.png", null, false, true, false, true, null)));
		DraftsStorage.PostDraft draft = controller.obtainPostDraft("fixture-chan", "test", null, " name ", " email ",
				"fixture-password", " subject ", "first\nsecond", 4, attachments, true, false, true, "user-icon");
		assertEquals("fixture-chan", draft.chanName); assertEquals("test", draft.boardName); assertNull(draft.threadNumber);
		assertEquals(" name ", draft.name); assertEquals(" email ", draft.email); assertEquals("fixture-password", draft.password);
		assertEquals(" subject ", draft.subject); assertEquals("first\nsecond", draft.comment); assertEquals(4, draft.commentCarriage);
		assertTrue(draft.optionSage); assertFalse(draft.optionSpoiler); assertTrue(draft.optionOriginalPoster);
		assertEquals("user-icon", draft.userIcon); assertSame(attachments, draft.attachmentDrafts);
		assertEquals("hash-b", draft.attachmentDrafts.get(0).hash); assertEquals("hash-a", draft.attachmentDrafts.get(1).hash);
	}

	@Test public void queuedMissingAndFailedDraftRestoreKeepsExistingPrecedence() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		DraftsStorage.PostDraft ordinary = post(controller), failed = post(controller);
		store.post = ordinary; store.failed = failed;
		assertSame(ordinary, controller.restorePostDraft("fixture-chan", "test", "123"));
		assertEquals(0, store.failedReads);
		store.queued = true;
		assertSame(failed, controller.restorePostDraft("fixture-chan", "test", "123"));
		store.failed = null;
		assertNull(controller.restorePostDraft("fixture-chan", "test", "123"));
		store.post = null; store.failed = failed;
		assertSame(failed, controller.restorePostDraft("fixture-chan", "test", "123"));
		assertEquals(3, store.failedReads);
	}

	@Test public void deferredAttachmentsAndCaptchaRemainSeparateStoragePaths() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		store.captcha = captcha(ReadCaptchaTask.CaptchaState.SKIP, null, "test", "123", null);
		store.future.add(new DraftsStorage.AttachmentDraft("hash", "file.png", null, false, false, false, false, null));
		assertSame(store.captcha, controller.getCaptchaDraft("fixture-chan"));
		assertSame(store.future, controller.getFutureAttachmentDrafts());
		assertEquals(0, store.consumed);
		controller.consumeFutureAttachmentDrafts();
		assertEquals(1, store.consumed); assertTrue(store.future.isEmpty()); assertEquals(0, store.postWrites);
	}

	@Test public void sendSnapshotCopiesInputAndPreservesAllStateDecisions() {
		for (ReadCaptchaTask.CaptchaState state : ReadCaptchaTask.CaptchaState.values()) {
			PostingCaptchaController controller = controller(new Host(), "test", "123");
			DraftsStorage.CaptchaDraft original = captcha(state, ChanConfiguration.Captcha.Validity.IN_THREAD, "test", "123", null);
			assertTrue(controller.restoreDraft(original, true, new ChanConfiguration.Captcha()));
			PostingCaptchaController.CaptchaSnapshot snapshot = controller.snapshot("typed-input");
			assertEquals("fixture-type", snapshot.type); assertSame(state, snapshot.state);
			assertEquals(ChanConfiguration.Captcha.Input.NUMERIC, snapshot.input);
			assertEquals(ChanConfiguration.Captcha.Validity.IN_THREAD, snapshot.validity);
			assertEquals("typed-input", snapshot.data.get(ChanPerformer.CaptchaData.INPUT));
			snapshot.data.put(ChanPerformer.CaptchaData.CHALLENGE, "changed-in-send");
			assertEquals("fixture-challenge", original.captchaData.get(ChanPerformer.CaptchaData.CHALLENGE));
			assertEquals("original-input", original.captchaData.get(ChanPerformer.CaptchaData.INPUT));
			assertEquals(state == ReadCaptchaTask.CaptchaState.MAY_LOAD || state == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING, snapshot.needLoad);
			assertEquals(state != ReadCaptchaTask.CaptchaState.NEED_LOAD, controller.canSend());
		}
		PostingCaptchaController empty = controller(new Host(), "test", null);
		assertFalse(empty.canSend()); assertNull(empty.snapshot("ignored").data);
	}

	@Test public void savedBundleRestoresImageTextFlagsTimeAndScopeIntoNewController() {
		Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		image.eraseColor(Color.BLUE);
		DraftsStorage.CaptchaDraft restored = null;
		try {
			restored = roundTrip(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, ChanConfiguration.Captcha.Validity.IN_THREAD, "test", "123", image));
			Host host = new Host();
			PostingCaptchaController controller = controller(host, "test", "123");
			assertTrue(controller.restoreDraft(restored, true, new ChanConfiguration.Captcha()));
			DraftsStorage.CaptchaDraft captured = controller.createDraft(host.text);
			assertEquals("fixture-type", captured.captchaType); assertEquals("12345", captured.text);
			assertEquals(restored.captchaData, captured.captchaData); assertNull(captured.loadedCaptchaType);
			assertEquals(restored.loadedInput, captured.loadedInput); assertEquals(restored.loadedValidity, captured.loadedValidity);
			assertSame(restored.image, captured.image); assertEquals(Color.BLUE, captured.image.getPixel(0, 0));
			assertTrue(captured.large); assertTrue(captured.blackAndWhite); assertEquals(123456789L, captured.loadTime);
			assertEquals("test", captured.boardName); assertEquals("123", captured.threadNumber);
			controller.detachView(); assertFalse(captured.image.isRecycled());
		} finally { image.recycle(); if (restored != null && restored.image != null) restored.image.recycle(); }
	}

	@Test public void storedCaptchaValidityRestrictsThreadBoardAndNewThreadScope() {
		PostingCaptchaController controller = controller(new Host(), "test", "123");
		DraftsStorage.CaptchaDraft same = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "test", "123", null);
		DraftsStorage.CaptchaDraft otherThread = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "test", "456", null);
		DraftsStorage.CaptchaDraft otherBoard = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "other", "123", null);
		DraftsStorage.CaptchaDraft newThread = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "test", null, null);
		assertFalse(controller.canRestoreStoredDraft(same, null));
		assertFalse(controller.canRestoreStoredDraft(same, ChanConfiguration.Captcha.Validity.SHORT_LIFETIME));
		assertTrue(controller.canRestoreStoredDraft(same, ChanConfiguration.Captcha.Validity.IN_THREAD));
		assertFalse(controller.canRestoreStoredDraft(otherThread, ChanConfiguration.Captcha.Validity.IN_THREAD));
		assertFalse(controller.canRestoreStoredDraft(otherBoard, ChanConfiguration.Captcha.Validity.IN_BOARD));
		assertTrue(controller.canRestoreStoredDraft(otherThread, ChanConfiguration.Captcha.Validity.IN_BOARD_SEPARATELY));
		assertFalse(controller.canRestoreStoredDraft(newThread, ChanConfiguration.Captcha.Validity.IN_BOARD_SEPARATELY));
		assertTrue(controller.canRestoreStoredDraft(newThread, ChanConfiguration.Captcha.Validity.IN_BOARD));
		assertTrue(controller.canRestoreStoredDraft(otherBoard, ChanConfiguration.Captcha.Validity.LONG_LIFETIME));
		DraftsStorage.CaptchaDraft reduced = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, ChanConfiguration.Captcha.Validity.IN_THREAD, "test", "456", null);
		assertFalse(controller.canRestoreStoredDraft(reduced, ChanConfiguration.Captcha.Validity.LONG_LIFETIME));
		assertFalse(controller.canRestoreStoredDraft(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, ChanConfiguration.Captcha.Validity.LONG_LIFETIME,
				"other", "123", null), ChanConfiguration.Captcha.Validity.IN_THREAD));
		DraftsStorage.CaptchaDraft different = new DraftsStorage.CaptchaDraft("other-type", same.captchaState, same.captchaData,
				null, null, null, null, null, false, false, 0, "test", "123");
		assertFalse(controller.canRestoreStoredDraft(different, ChanConfiguration.Captcha.Validity.LONG_LIFETIME));
		DraftsStorage.CaptchaDraft loadedType = new DraftsStorage.CaptchaDraft("fixture-type", same.captchaState, same.captchaData,
				"dynamic-type", null, null, null, null, false, false, 0, "test", "123");
		assertFalse(controller.canRestoreStoredDraft(loadedType, ChanConfiguration.Captcha.Validity.LONG_LIFETIME));
	}

	@Test public void storedImageAndPassRestoreKeepDifferentInputRules() {
		Host host = new Host();
		PostingCaptchaController controller = controller(host, "test", "123");
		ChanConfiguration.Captcha config = new ChanConfiguration.Captcha();
		assertFalse(controller.restoreDraft(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "test", "123", null), false, config));
		Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		try {
			assertTrue(controller.restoreDraft(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA, null, "test", "123", image), false, config));
			assertEquals("12345", host.text); assertEquals(1, host.inputs);
			for (ReadCaptchaTask.CaptchaState state : new ReadCaptchaTask.CaptchaState[] {ReadCaptchaTask.CaptchaState.SKIP, ReadCaptchaTask.CaptchaState.PASS}) {
				assertTrue(controller.restoreDraft(captcha(state, null, "test", "123", null), false, config));
				assertSame(state, host.state); assertEquals(1, host.inputs); assertNull(host.image);
			}
			assertTrue(image.isRecycled());
		} finally { if (!image.isRecycled()) image.recycle(); }
	}

	@Test public void detachedHostGetsNoCallbacksAndReattachedOwnerUsesNewHost() {
		Host first = new Host();
		PostingCaptchaController controller = controller(first, "test", "123");
		ReadCaptchaTask.Result result = new ReadCaptchaTask.Result(ReadCaptchaTask.CaptchaState.PASS, null,
				null, null, null, null, false, false);
		controller.onReadCaptchaSuccess(result);
		assertEquals(987654321L, controller.createDraft(null).loadTime);
		controller.detachView();
		controller.onReadCaptchaSuccess(result); controller.onReadCaptchaError(new ErrorItem(ErrorItem.Type.UNKNOWN));
		assertEquals(1, first.shown); assertEquals(1, first.configurations); assertEquals(0, first.errors);
		Host second = new Host(); controller.attachView(second);
		controller.onReadCaptchaSuccess(result); controller.onReadCaptchaError(new ErrorItem(ErrorItem.Type.UNKNOWN));
		assertEquals(1, second.shown); assertEquals(1, second.errors); assertEquals(2, second.configurations);
		assertEquals(1, first.shown);
	}

	@Test public void recreatedDraftOwnerStartsWithFreshGuardWithoutRetainingUi() {
		Store store = new Store();
		PostingDraftController first = new PostingDraftController(store);
		first.saveDraft(false, "fixture-chan", () -> post(first), () -> null);
		PostingDraftController second = new PostingDraftController(store);
		DraftsStorage.PostDraft restored = second.restorePostDraft("fixture-chan", "test", "123");
		assertSame(store.post, restored); assertEquals(7, restored.commentCarriage);
		second.saveDraft(false, "fixture-chan", () -> restored, () -> null);
		assertEquals(2, store.postWrites); assertEquals(2, store.captchaWrites);
	}
}
