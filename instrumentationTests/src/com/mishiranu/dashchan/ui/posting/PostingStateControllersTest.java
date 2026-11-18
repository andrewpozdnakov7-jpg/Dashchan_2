package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

	@Test public void rolloverRestorationNeverConsumesTheFailedSendQueue() {
		Store store = new Store();
		PostingDraftController controller = new PostingDraftController(store);
		store.failed = post(controller);
		assertNull(controller.restoreRolloverDraft("fixture-chan", "test"));
		store.post = RolloverThreadDialog.createDraft("fixture-chan", "test", "New subject", "New OP", new ArrayList<>());
		assertSame(store.post, controller.restoreRolloverDraft("fixture-chan", "test"));
		store.queued = true;
		assertNull(controller.restoreRolloverDraft("fixture-chan", "test"));
		assertEquals(0, store.failedReads); assertEquals(0, store.postWrites);
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
			Bitmap image = state == ReadCaptchaTask.CaptchaState.CAPTCHA
					? Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) : null;
			DraftsStorage.CaptchaDraft original = captcha(state, ChanConfiguration.Captcha.Validity.IN_THREAD, "test", "123", image);
			try {
				assertTrue(controller.restoreDraft(original, true, new ChanConfiguration.Captcha()));
			} finally {
				if (image != null) image.recycle();
			}
			PostingCaptchaController.CaptchaSnapshot snapshot = controller.snapshot("typed-input");
			assertEquals("fixture-type", snapshot.type); assertSame(state, snapshot.state);
			assertEquals(ChanConfiguration.Captcha.Input.NUMERIC, snapshot.input);
			assertEquals(ChanConfiguration.Captcha.Validity.IN_THREAD, snapshot.validity);
			assertEquals("typed-input", snapshot.data.get(ChanPerformer.CaptchaData.INPUT));
			snapshot.data.put(ChanPerformer.CaptchaData.CHALLENGE, "changed-in-send");
			assertEquals("fixture-challenge", original.captchaData.get(ChanPerformer.CaptchaData.CHALLENGE));
			assertEquals("original-input", original.captchaData.get(ChanPerformer.CaptchaData.INPUT));
			assertEquals(state == ReadCaptchaTask.CaptchaState.MAY_LOAD, snapshot.needLoad);
			assertEquals(state != ReadCaptchaTask.CaptchaState.NEED_LOAD, controller.canSend());
		}
		PostingCaptchaController empty = controller(new Host(), "test", null);
		assertFalse(empty.canSend()); assertNull(empty.snapshot("ignored").data);
	}

	@Test public void savedGraphicCaptchaWithMissingOrRecycledImageIsRejected() {
		Host host = new Host();
		PostingCaptchaController controller = controller(host, "test", "123");
		ChanConfiguration.Captcha config = new ChanConfiguration.Captcha();
		assertFalse(controller.restoreDraft(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA,
				null, "test", "123", null), true, config));
		Bitmap recycled = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		recycled.recycle();
		assertFalse(controller.restoreDraft(captcha(ReadCaptchaTask.CaptchaState.CAPTCHA,
				null, "test", "123", recycled), true, config));
		assertEquals(0, host.inputs);
		assertFalse(controller.canSend());
	}

	@Test public void retainedChallengeUsesAFixedSizeKeyScopedToTheForm() {
		Bitmap small = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		Bitmap large = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
		PostingCaptchaController.CaptchaViewModel model = new PostingCaptchaController.CaptchaViewModel();
		try {
			DraftsStorage.CaptchaDraft smallDraft = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA,
					null, "test", "123", small);
			String smallKey = model.retainDraft(smallDraft);
			assertSame(smallDraft, model.getRetainedDraft(smallKey));
			DraftsStorage.CaptchaDraft largeDraft = captcha(ReadCaptchaTask.CaptchaState.CAPTCHA,
					null, "test", "123", large);
			String largeKey = model.retainDraft(largeDraft);
			assertSame(largeDraft, model.getRetainedDraft(largeKey));
			assertNull(model.getRetainedDraft(smallKey));
			assertNull(new PostingCaptchaController.CaptchaViewModel().getRetainedDraft(largeKey));
			assertEquals(parcelKeySize(smallKey), parcelKeySize(largeKey));
		} finally {
			small.recycle();
			large.recycle();
		}
	}

	private static int parcelKeySize(String key) {
		Bundle bundle = new Bundle();
		bundle.putString("captchaDraftKey", key);
		Parcel parcel = Parcel.obtain();
		try {
			parcel.writeBundle(bundle);
			return parcel.dataSize();
		} finally {
			parcel.recycle();
		}
	}

	@Test public void supportedTextOnlyCaptchaCanBeRestoredAfterLosingTheViewModel() {
		ChanConfiguration.Captcha config = new ChanConfiguration.Captcha();
		config.ttl = 90;
		long[] elapsed = {15000L};
		PostingCaptchaController controller = new PostingCaptchaController(() -> elapsed[0]);
		controller.configure("fixture-chan", "test", "123", "fixture-type");
		ChanPerformer.CaptchaData answer = new ChanPerformer.CaptchaData();
		answer.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-solution");
		DraftsStorage.CaptchaDraft solved = new DraftsStorage.CaptchaDraft("fixture-type",
				ReadCaptchaTask.CaptchaState.SKIP, answer, null, null, null, null, null,
				false, false, elapsed[0], "test", "123");
		Bundle saved = controller.saveReusableDraft(solved, config);
		assertNotNull(saved);
		assertEquals("fixture-solution", controller.restoreReusableDraft(saved, config)
				.captchaData.get(ChanPerformer.CaptchaData.CHALLENGE));
		controller.configure("fixture-chan", "test", "124", "fixture-type");
		assertNull(controller.restoreReusableDraft(saved, config));
		controller.configure("fixture-chan", "test", "123", "fixture-type");
		assertNull(controller.restoreReusableDraft(saved, new ChanConfiguration.Captcha()));
		DraftsStorage.CaptchaDraft pass = new DraftsStorage.CaptchaDraft("fixture-type",
				ReadCaptchaTask.CaptchaState.PASS, null, null, null, null, null, null,
				false, false, elapsed[0], "test", "123");
		assertSame(ReadCaptchaTask.CaptchaState.PASS,
				controller.restoreReusableDraft(controller.saveReusableDraft(pass, config), config).captchaState);
		DraftsStorage.CaptchaDraft noChallenge = new DraftsStorage.CaptchaDraft("fixture-type",
				ReadCaptchaTask.CaptchaState.SKIP, null, null, null, null, null, null,
				false, false, elapsed[0], "test", "123");
		assertNotNull(controller.restoreReusableDraft(controller.saveReusableDraft(noChallenge, config), config));
		answer.put("pass_cookie", "fixture-secret");
		assertNull(controller.saveReusableDraft(solved, config));
		elapsed[0] += 61000L;
		assertNull(controller.restoreReusableDraft(saved, config));
	}

	/** A real posting screen with its first challenge supplied locally. */
	public static class CaptchaSavedStateFragment extends PostingFragment {
		static final AtomicInteger fixturesCreated = new AtomicInteger();

		public CaptchaSavedStateFragment() {
			super("dvach", "test", "503000001", Collections.emptyList());
		}

		@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
			if (savedInstanceState == null) {
				fixturesCreated.incrementAndGet();
				savedInstanceState = new Bundle();
				Bitmap image = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
				// Make compression of the image meaningfully larger than the saved-state bound.
				Random random = new Random(42L);
				int[] row = new int[400];
				for (int y = 0; y < 400; y++) {
					for (int x = 0; x < row.length; x++) row[x] = random.nextInt() | 0xff000000;
					image.setPixels(row, 0, row.length, 0, y, row.length, 1);
				}
				String captchaType = Chan.get("dvach").configuration.getCaptchaType();
				DraftsStorage.CaptchaDraft draft = new DraftsStorage.CaptchaDraft(captchaType,
						ReadCaptchaTask.CaptchaState.CAPTCHA, null, null, ChanConfiguration.Captcha.Input.ALL,
						ChanConfiguration.Captcha.Validity.IN_THREAD, "fixture answer", image,
						false, false, SystemClock.elapsedRealtime(), "test", "503000001");
				savedInstanceState.putString("captchaDraftKey", new ViewModelProvider(this)
						.get(PostingCaptchaController.CaptchaViewModel.class).retainDraft(draft));
			}
			super.onViewCreated(view, savedInstanceState);
		}
	}

	@Test public void realPostingSavedStateIsSmallAndRetainsGraphicCaptchaAcrossRecreation() {
		CaptchaSavedStateFragment.fixturesCreated.set(0);
		try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
			scenario.onActivity(activity -> activity.pushFragment(new CaptchaSavedStateFragment()));
			checkGraphicCaptcha(scenario);
			scenario.recreate();
			checkGraphicCaptcha(scenario);
			assertEquals(1, CaptchaSavedStateFragment.fixturesCreated.get());
		}
	}

	@Test public void graphicCaptchaDialogSurvivesPauseAndRecreation() throws Exception {
		String motion = Preferences.KEY_NEW_INTERFACE_MOTION, form = Preferences.KEY_NEW_POSTING_FORM;
		Object oldMotion = Preferences.PREFERENCES.getAll().get(motion), oldForm = Preferences.PREFERENCES.getAll().get(form);
		try {
			Preferences.PREFERENCES.edit().put(motion, true).put(form, true).close();
			try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
				AtomicReference<PostingFragment> first = new AtomicReference<>();
				scenario.onActivity(activity -> activity.pushFragment(new CaptchaSavedStateFragment()));
				checkGraphicCaptcha(scenario);
				scenario.onActivity(activity -> {
					PostingFragment posting = posting(activity);
					first.set(posting);
					EditText input = posting.requireView().findViewById(R.id.captcha_input);
					input.setText("");
					appearance(posting).requestCaptchaSolution();
					input.setText("part");
					input.setSelection(1, 3);
				});
				awaitPartialDialog(scenario);
				scenario.moveToState(Lifecycle.State.CREATED);
				scenario.moveToState(Lifecycle.State.RESUMED);
				awaitPartialDialog(scenario);
				scenario.onActivity(activity -> assertSame(first.get(), posting(activity)));
				scenario.recreate();
				AlertDialog restored = awaitPartialDialog(scenario);
				scenario.onActivity(activity -> restored.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
				scenario.moveToState(Lifecycle.State.CREATED);
				scenario.moveToState(Lifecycle.State.RESUMED);
				scenario.onActivity(activity -> {
					PostingFragment posting = posting(activity);
					assertNull(appearance(posting).captureImageCaptchaDialogState());
					EditText input = posting.requireView().findViewById(R.id.captcha_input);
					assertNotNull(input); // Back in the page, with the original unconfirmed answer.
					assertEquals("", input.getText().toString());
				});
				// Pause with another partial answer, then replace the challenge before resuming.
				scenario.onActivity(activity -> {
					PostingFragment posting = posting(activity);
					EditText input = posting.requireView().findViewById(R.id.captcha_input);
					appearance(posting).requestCaptchaSolution();
					input.setText("part");
					input.setSelection(1, 3);
				});
				awaitPartialDialog(scenario);
				scenario.moveToState(Lifecycle.State.STARTED);
				Field field = PostingFragment.class.getDeclaredField("captchaController");
				field.setAccessible(true);
				scenario.onActivity(activity -> {
					try {
						PostingCaptchaController controller = (PostingCaptchaController) field.get(posting(activity));
						String type = Chan.get("dvach").configuration.getCaptchaType();
						DraftsStorage.CaptchaDraft replacement = new DraftsStorage.CaptchaDraft(type,
								ReadCaptchaTask.CaptchaState.CAPTCHA, null, null, ChanConfiguration.Captcha.Input.ALL,
								ChanConfiguration.Captcha.Validity.IN_THREAD, "", Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
								false, false, controller.getLoadTime() + 1, "test", "503000001");
						assertTrue(controller.restoreDraft(replacement, true, Chan.get("dvach").configuration.safe().obtainCaptcha(type)));
					} catch (IllegalAccessException e) {
						throw new AssertionError(e);
					}
				});
				scenario.moveToState(Lifecycle.State.RESUMED);
				scenario.onActivity(activity -> {
					PostingFragment posting = posting(activity);
					Bundle saved = new Bundle();
					posting.onSaveInstanceState(saved);
					assertFalse(saved.containsKey("imageCaptchaDialog"));
					assertNull(appearance(posting).captureImageCaptchaDialogState());
					EditText input = posting.requireView().findViewById(R.id.captcha_input);
					assertEquals("", input.getText().toString());
				});
			}
		} finally {
			restorePreference(motion, oldMotion);
			restorePreference(form, oldForm);
		}
	}

	private static void restorePreference(String key, Object previous) {
		if (previous instanceof Boolean old) Preferences.PREFERENCES.edit().put(key, old).close();
		else Preferences.PREFERENCES.edit().remove(key).close();
	}

	private static PostingFragment posting(MainActivity activity) {
		return (PostingFragment) activity.getSupportFragmentManager().findFragmentById(R.id.content_fragment);
	}

	private static PostingFormAppearance appearance(PostingFragment fragment) {
		return (PostingFormAppearance) fragment.requireView().getTag(R.id.posting_form_owner);
	}

	private static AlertDialog awaitPartialDialog(ActivityScenario<MainActivity> scenario) throws NoSuchFieldException {
		Field dialogField = PostingFormAppearance.class.getDeclaredField("captchaDialog");
		dialogField.setAccessible(true);
		AtomicReference<AlertDialog> result = new AtomicReference<>();
		long deadline = SystemClock.elapsedRealtime() + 5000L;
		do {
			scenario.onActivity(activity -> {
				Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.content_fragment);
				if (!(fragment instanceof CaptchaSavedStateFragment posting) || posting.getView() == null) return;
				PostingFormAppearance appearance = appearance(posting);
				Bundle dialog = appearance.captureImageCaptchaDialogState();
				if (dialog == null) return;
				assertEquals("part", dialog.getString("input"));
				assertEquals(1, dialog.getInt("selectionStart"));
				assertEquals(3, dialog.getInt("selectionEnd"));
				try {
					result.set((AlertDialog) dialogField.get(appearance));
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
			});
			if (result.get() != null) return result.get();
			SystemClock.sleep(20L);
		} while (SystemClock.elapsedRealtime() < deadline);
		throw new AssertionError("Graphic captcha dialog did not reopen");
	}

	private static void checkGraphicCaptcha(ActivityScenario<MainActivity> scenario) {
		long deadline = SystemClock.elapsedRealtime() + 5000L;
		AtomicBoolean checked = new AtomicBoolean();
		do {
			scenario.onActivity(activity -> {
				Fragment fragment = activity.getSupportFragmentManager().findFragmentById(R.id.content_fragment);
				if (!(fragment instanceof CaptchaSavedStateFragment posting) || posting.getView() == null) return;
				ImageView image = posting.requireView().findViewById(R.id.captcha_image);
				assertTrue(image.getDrawable() instanceof BitmapDrawable);
				assertEquals(1600, ((BitmapDrawable) image.getDrawable()).getBitmap().getWidth());
				android.widget.EditText input = posting.requireView().findViewById(R.id.captcha_input);
				assertEquals("fixture answer", input.getText().toString());
				Fragment.SavedState state = activity.getSupportFragmentManager().saveFragmentInstanceState(posting);
				assertNotNull(state);
				Parcel parcel = Parcel.obtain();
				try {
					state.writeToParcel(parcel, 0);
					assertTrue("Posting state must remain small with a large captcha", parcel.dataSize() < 64 * 1024);
				} finally {
					parcel.recycle();
				}
				checked.set(true);
			});
			if (checked.get()) return;
			SystemClock.sleep(20L);
		} while (SystemClock.elapsedRealtime() < deadline);
		fail("Posting fixture did not become visible");
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
