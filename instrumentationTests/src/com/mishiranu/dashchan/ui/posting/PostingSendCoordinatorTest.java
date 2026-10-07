package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.async.SendPostTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual snapshot/coordinator, injected service and in-memory drafts. Never binds/sends to a real service. */
@RunWith(AndroidJUnit4.class)
public class PostingSendCoordinatorTest {
	private static final ComponentName SERVICE = new ComponentName("fixture", "fixture.PostingService");
	private static class Store implements PostingDraftController.Store {
		int postWrites, captchaWrites;
		final ArrayList<String> events = new ArrayList<>();
		@Override public DraftsStorage.PostDraft getPostDraft(String chan, String board, String thread) { return null; }
		@Override public boolean isPostDraftQueued(DraftsStorage.PostDraft draft) { return false; }
		@Override public DraftsStorage.PostDraft restoreFailedPostDraft(String chan, String board, String thread) { return null; }
		@Override public DraftsStorage.CaptchaDraft getCaptchaDraft(String chan) { return null; }
		@Override public ArrayList<DraftsStorage.AttachmentDraft> getFutureAttachmentDrafts() { return new ArrayList<>(); }
		@Override public void consumeFutureAttachmentDrafts() {}
		@Override public void storePost(DraftsStorage.PostDraft draft) { postWrites++; events.add("post"); }
		@Override public void storeCaptcha(String chan, DraftsStorage.CaptchaDraft draft) { captchaWrites++; events.add("captcha"); }
	}
	private static class Host implements PostingSendCoordinator.Host {
		boolean resumed = true, enabled = true, allowDialog, mayLoad;
		int states, progressEvents, dismisses, forgotten, closes, failures, refreshes, configurations;
		long progress, maximum;
		int index, count;
		boolean progressMode;
		SendPostTask.ProgressState state;
		PostingService.FailResult failure;
		@Override public boolean isResumed() { return resumed; }
		@Override public void showPostingState(boolean allow, boolean mode, SendPostTask.ProgressState state, int index, int count) {
			states++; allowDialog = allow; progressMode = mode; this.state = state; this.index = index; this.count = count;
		}
		@Override public void updatePostingProgress(long progress, long maximum) { progressEvents++; this.progress = progress; this.maximum = maximum; }
		@Override public void dismissPostingProgress() { dismisses++; }
		@Override public void forgetPostingProgress() { forgotten++; }
		@Override public void setSendButtonEnabled(boolean enabled) { this.enabled = enabled; }
		@Override public void closePostingFragment() { closes++; }
		@Override public void showPostingFailure(PostingService.FailResult failure) { failures++; this.failure = failure; }
		@Override public void refreshCaptcha(boolean mayLoad) { refreshes++; this.mayLoad = mayLoad; }
		@Override public void updatePostingConfigurationIfNeeded() { configurations++; }
	}
	private static class Binding implements PostingSendCoordinator.Binding {
		int binds, unbinds;
		boolean accepted = true;
		ServiceConnection connection;
		@Override public boolean bind(ServiceConnection connection) { binds++; this.connection = connection; return accepted; }
		@Override public void unbind(ServiceConnection connection) { assertSame(this.connection, connection); unbinds++; }
		void connect() { connection.onServiceConnected(SERVICE, null); }
	}
	private static class Endpoint implements PostingSendCoordinator.Endpoint {
		PostingService.Callback callback;
		int registers, unregisters, sends, cancels;
		boolean accepted = true, flood;
		String chan, board, thread;
		ChanPerformer.SendPostData request;
		DraftsStorage.PostDraft draft;
		ArrayList<String> hashes;
		final Store store;
		Endpoint(Store store) { this.store = store; }
		@Override public void register(PostingService.Callback callback, String chan, String board, String thread) {
			this.callback = callback; this.chan = chan; this.board = board; this.thread = thread; registers++;
		}
		@Override public void unregister(PostingService.Callback callback) { assertSame(this.callback, callback); unregisters++; }
		@Override public boolean send(String chan, ChanPerformer.SendPostData request, DraftsStorage.PostDraft draft,
				Collection<String> hashes, boolean flood) {
			assertEquals(Arrays.asList("post", "captcha"), store.events);
			store.events.add("send"); sends++; this.request = request; this.draft = draft;
			this.hashes = new ArrayList<>(hashes); this.flood = flood;
			callback.onState(true, SendPostTask.ProgressState.CONNECTING, 0, 2);
			return accepted;
		}
		@Override public void cancel(String chan, String board, String thread) {
			assertEquals(this.chan, chan); assertEquals(this.board, board); assertEquals(this.thread, thread); cancels++;
		}
	}
	private static class Fixture {
		final Store store = new Store();
		final PostingDraftController drafts = new PostingDraftController(store);
		final Host host = new Host();
		final Binding binding = new Binding();
		final Endpoint endpoint = new Endpoint(store);
		final PostingSendCoordinator coordinator = new PostingSendCoordinator(drafts, binder -> endpoint);
		Fixture() { coordinator.bind(host, binding, "fixture-chan", "test", "123"); binding.connect(); }
		DraftsStorage.PostDraft post() {
			return drafts.obtainPostDraft("fixture-chan", "test", "123", null, null, null, null, "fixture", 2,
					new ArrayList<>(), false, false, false, null);
		}
	}
	private static PostingCaptchaController.CaptchaSnapshot captcha(ReadCaptchaTask.CaptchaState state) {
		ChanPerformer.CaptchaData data = new ChanPerformer.CaptchaData();
		data.put(ChanPerformer.CaptchaData.INPUT, "fixture-input");
		data.put(ChanPerformer.CaptchaData.CHALLENGE, "fixture-challenge");
		return new PostingCaptchaController.CaptchaSnapshot("fixture-type", state, data,
				ChanConfiguration.Captcha.Input.ALL, ChanConfiguration.Captcha.Validity.IN_THREAD);
	}
	private static PostingFormData form(ReadCaptchaTask.CaptchaState state) {
		return new PostingFormData("subject", "comment", "name", "email", "fixture-password", true, false, true,
				"icon", new PostingAttachmentsController.SendAttachments(null, new ArrayList<>()), captcha(state));
	}
	private static PostingService.FailResult failure(int http, boolean captchaError, boolean keepCaptcha) {
		ErrorItem error = http == 0 ? new ErrorItem(ErrorItem.Type.UNKNOWN) : new ErrorItem(http, "fixture-error");
		return new PostingService.FailResult(error, null, captchaError, keepCaptcha);
	}

	@Test public void formPayloadKeepsFieldsOrderFlagsAndDetachedContainers() {
		ChanPerformer.SendPostData.Attachment first = new ChanPerformer.SendPostData.Attachment(null, "b.png", "r", true, false, true, false, null);
		ChanPerformer.SendPostData.Attachment second = new ChanPerformer.SendPostData.Attachment(null, "a.png", null, false, true, false, true, null);
		ChanPerformer.SendPostData.Attachment[] attachments = {first, second};
		ArrayList<String> hashes = new ArrayList<>(Arrays.asList("hash-b", "hash-a"));
		PostingCaptchaController.CaptchaSnapshot captcha = captcha(ReadCaptchaTask.CaptchaState.PASS);
		PostingFormData snapshot = new PostingFormData("subject", "comment", "name", "email", "fixture-password",
				true, false, true, "icon", new PostingAttachmentsController.SendAttachments(attachments, hashes), captcha);
		attachments[0] = second; hashes.clear(); captcha.data.put(ChanPerformer.CaptchaData.INPUT, "outside-change");
		ChanPerformer.SendPostData data = snapshot.createRequest("test", "123");
		assertEquals("test", data.boardName); assertEquals("123", data.threadNumber);
		assertEquals("subject", data.subject); assertEquals("comment", data.comment); assertEquals("name", data.name);
		assertEquals("email", data.email); assertEquals("fixture-password", data.password); assertEquals("icon", data.userIcon);
		assertTrue(data.optionSage); assertFalse(data.optionSpoiler); assertTrue(data.optionOriginalPoster);
		assertSame(first, data.attachments[0]); assertSame(second, data.attachments[1]);
		assertEquals("r", data.attachments[0].rating); assertTrue(data.attachments[0].optionUniqueHash);
		assertTrue(data.attachments[0].optionRemoveFileName); assertTrue(data.attachments[1].optionRemoveMetadata);
		assertTrue(data.attachments[1].optionSpoiler); assertEquals("fixture-type", data.captchaType);
		assertEquals("fixture-input", data.captchaData.get(ChanPerformer.CaptchaData.INPUT));
		assertEquals(15000, data.connectTimeout); assertEquals(45000, data.readTimeout); assertTrue(snapshot.allowFloodRetry());
		data.attachments[0] = second; data.captchaData.put(ChanPerformer.CaptchaData.INPUT, "request-change");
		assertSame(first, snapshot.createRequest("test", "123").attachments[0]);
		assertEquals("fixture-input", snapshot.createRequest("test", "123").captchaData.get(ChanPerformer.CaptchaData.INPUT));
		assertEquals(Arrays.asList("hash-b", "hash-a"), snapshot.createAttachmentHashes());
		snapshot.createAttachmentHashes().clear(); assertEquals(2, snapshot.createAttachmentHashes().size());
	}

	@Test public void emptyNewThreadAndCaptchaStatesKeepNullAndLoadSemantics() {
		for (ReadCaptchaTask.CaptchaState state : ReadCaptchaTask.CaptchaState.values()) {
			PostingFormData snapshot = new PostingFormData(null, null, null, null, null, false, false, false, null,
					new PostingAttachmentsController.SendAttachments(null, new ArrayList<>()), captcha(state));
			ChanPerformer.SendPostData data = snapshot.createRequest("test", null);
			assertNull(data.threadNumber); assertNull(data.attachments); assertNull(data.subject); assertNull(data.comment);
			assertNull(data.name); assertNull(data.email); assertNull(data.password); assertNull(data.userIcon);
			assertFalse(data.optionSage || data.optionSpoiler || data.optionOriginalPoster);
			assertTrue(snapshot.createAttachmentHashes().isEmpty());
			assertEquals(state == ReadCaptchaTask.CaptchaState.MAY_LOAD || state == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING, data.captchaNeedLoad);
			assertEquals(state == ReadCaptchaTask.CaptchaState.PASS, snapshot.allowFloodRetry());
		}
	}

	@Test public void hiddenFieldsAndPasswordFallbackUseRealUiHelpersWithoutChangingPreferences() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				PostingFragment fragment = new PostingFragment();
				Method text = PostingFragment.class.getDeclaredMethod("getTextIfVisible", EditText.class);
				Method checked = PostingFragment.class.getDeclaredMethod("isCheckedIfVisible", CheckBox.class);
				text.setAccessible(true); checked.setAccessible(true);
				EditText input = new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
				input.setText("typed"); assertEquals("typed", text.invoke(fragment, input));
				input.setVisibility(View.GONE); assertNull(text.invoke(fragment, input));
				input.setVisibility(View.INVISIBLE); assertNull(text.invoke(fragment, input));
				input.setVisibility(View.VISIBLE); input.setText(""); assertNull(text.invoke(fragment, input));
				assertEquals("fallback", PostingFormData.resolvePassword((String) text.invoke(fragment, input), () -> "fallback"));
				input.setText(" "); assertEquals(" ", text.invoke(fragment, input));
				assertEquals("typed", PostingFormData.resolvePassword("typed", () -> { throw new AssertionError("unneeded fallback"); }));
				CheckBox option = new CheckBox(input.getContext()); option.setChecked(true);
				assertEquals(Boolean.TRUE, checked.invoke(fragment, option)); option.setVisibility(View.GONE);
				assertEquals(Boolean.FALSE, checked.invoke(fragment, option));
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		});
	}

	@Test public void bindRegisterDisconnectReconnectAndUnbindHaveSingleOwnership() {
		Fixture f = new Fixture();
		f.coordinator.bind(f.host, f.binding, "fixture-chan", "test", "123");
		assertEquals(1, f.binding.binds); assertEquals(1, f.endpoint.registers); assertTrue(f.coordinator.canSend());
		assertEquals("fixture-chan", f.endpoint.chan); assertEquals("test", f.endpoint.board); assertEquals("123", f.endpoint.thread);
		f.binding.connection.onServiceDisconnected(SERVICE); assertFalse(f.coordinator.canSend());
		assertEquals(1, f.endpoint.unregisters); f.binding.connect(); assertTrue(f.coordinator.canSend());
		assertEquals(2, f.endpoint.registers); f.coordinator.unbind(); f.coordinator.unbind();
		assertEquals(2, f.endpoint.unregisters); assertEquals(1, f.binding.unbinds); assertEquals(0, f.endpoint.cancels);
		assertFalse(f.coordinator.canSend());
	}

	@Test public void oldConnectionAndCallbacksCannotPublishIntoRecreatedView() {
		Fixture f = new Fixture();
		ServiceConnection oldConnection = f.binding.connection;
		PostingService.Callback oldCallback = f.endpoint.callback;
		f.coordinator.unbind();
		Host nextHost = new Host(); Binding nextBinding = new Binding();
		f.coordinator.bind(nextHost, nextBinding, "fixture-chan", "test", "123"); nextBinding.connect();
		oldConnection.onServiceConnected(SERVICE, null); oldConnection.onServiceDisconnected(SERVICE);
		oldCallback.onState(true, SendPostTask.ProgressState.SENDING, 0, 2);
		oldCallback.onProgress(1000, 2000); oldCallback.onStop(true);
		assertEquals(2, f.endpoint.registers); assertEquals(1, f.endpoint.unregisters);
		assertEquals(0, nextHost.states); assertEquals(0, nextHost.progressEvents); assertEquals(0, nextHost.closes);
		assertFalse(f.coordinator.isSendSuccess()); assertTrue(f.coordinator.canSend());
		f.coordinator.unbind();
	}

	@Test public void progressFailureStopAndDismissAreForwardedWithoutCancelling() {
		Fixture f = new Fixture();
		for (SendPostTask.ProgressState state : SendPostTask.ProgressState.values()) {
			f.endpoint.callback.onState(true, state, 1, 3);
			assertSame(state, f.host.state); assertEquals(1, f.host.index); assertEquals(3, f.host.count);
			assertTrue(f.host.progressMode); assertTrue(f.host.allowDialog);
		}
		f.endpoint.callback.onProgress(2345, 9876); assertEquals(2345L, f.host.progress); assertEquals(9876L, f.host.maximum);
		f.host.enabled = false; f.endpoint.callback.onStop(false);
		assertTrue(f.host.enabled); assertEquals(1, f.host.dismisses); assertEquals(0, f.host.closes);
		assertFalse(f.coordinator.isSendSuccess()); assertEquals(0, f.endpoint.cancels); f.coordinator.unbind();
	}

	@Test public void successWhilePausedKeepsFlagForResumeAndDoesNotResurrectDraft() {
		Fixture f = new Fixture(); f.host.resumed = false;
		f.endpoint.callback.onStop(true);
		assertTrue(f.coordinator.isSendSuccess()); assertEquals(0, f.host.closes);
		f.drafts.resetSavedGuard(); f.drafts.saveDraft(f.coordinator.isSendSuccess(), "fixture-chan",
				() -> { throw new AssertionError("successful draft capture"); }, () -> null);
		assertEquals(0, f.store.postWrites); f.coordinator.unbind();
		Fixture resumed = new Fixture(); resumed.endpoint.callback.onStop(true);
		assertEquals(1, resumed.host.closes); resumed.coordinator.unbind();
	}

	@Test public void failuresStayDeferredAndCaptchaRefreshConditionsStayExact() {
		Fixture f = new Fixture(); f.host.resumed = false;
		f.coordinator.handleFailResult(failure(0, false, false));
		PostingService.FailResult last = failure(0, true, false); f.coordinator.handleFailResult(last);
		f.coordinator.deliverPendingUiEvents(); assertEquals(0, f.host.failures);
		f.host.resumed = true; f.coordinator.deliverPendingUiEvents();
		assertSame(last, f.host.failure); assertEquals(1, f.host.failures); assertEquals(1, f.host.refreshes); assertFalse(f.host.mayLoad);
		f.coordinator.deliverPendingUiEvents(); assertEquals(1, f.host.failures);
		f.coordinator.handleFailResult(failure(0, false, false)); assertTrue(f.host.mayLoad); assertEquals(2, f.host.refreshes);
		f.coordinator.handleFailResult(failure(403, false, false));
		f.coordinator.handleFailResult(failure(0, false, true));
		assertEquals(2, f.host.refreshes); assertEquals(4, f.host.configurations); f.coordinator.unbind();
	}

	@Test public void acceptedSendStoresBeforeHandoffMinimizesAndKeepsSaveGuard() {
		Fixture f = new Fixture(); DraftsStorage.PostDraft post = f.post();
		f.coordinator.send(form(ReadCaptchaTask.CaptchaState.PASS), () -> post, () -> null);
		assertEquals(Arrays.asList("post", "captcha", "send"), f.store.events);
		assertSame(post, f.endpoint.draft); assertEquals("123", f.endpoint.request.threadNumber); assertTrue(f.endpoint.flood);
		assertEquals(1, f.endpoint.sends); assertEquals(0, f.endpoint.cancels); assertFalse(f.host.allowDialog);
		assertFalse(f.host.enabled); assertEquals(1, f.host.closes); assertEquals(1, f.host.dismisses);
		f.drafts.saveDraft(false, "fixture-chan", () -> { throw new AssertionError("queued draft captured again"); }, () -> null);
		assertEquals(1, f.store.postWrites); f.coordinator.unbind();
	}

	@Test public void rejectedSendRestoresDialogPolicyAndSaveGuardWithoutClosing() {
		Fixture f = new Fixture(); f.endpoint.accepted = false;
		f.coordinator.send(form(ReadCaptchaTask.CaptchaState.CAPTCHA), f::post, () -> null);
		assertFalse(f.endpoint.flood); assertEquals(0, f.host.closes); assertTrue(f.host.enabled);
		f.endpoint.callback.onState(false, SendPostTask.ProgressState.CONNECTING, 0, 0); assertTrue(f.host.allowDialog);
		f.drafts.saveDraft(false, "fixture-chan", f::post, () -> null); assertEquals(2, f.store.postWrites);
		f.coordinator.unbind();
	}

	@Test public void cancelAndMinimizeRemainDifferentOperations() {
		Fixture minimized = new Fixture(); minimized.coordinator.minimize();
		assertEquals(1, minimized.host.forgotten); assertEquals(1, minimized.host.closes); assertEquals(0, minimized.endpoint.cancels);
		minimized.coordinator.unbind(); assertEquals(0, minimized.endpoint.cancels);
		Fixture cancelled = new Fixture(); cancelled.coordinator.cancel();
		assertEquals(1, cancelled.host.forgotten); assertEquals(1, cancelled.endpoint.cancels); assertEquals(0, cancelled.host.closes);
		cancelled.coordinator.unbind(); assertEquals(1, cancelled.endpoint.cancels);
	}

	@Test public void failedBindingAndInlineConnectionDoNotLeakOrUnbindTwice() {
		Store store = new Store(); PostingDraftController drafts = new PostingDraftController(store);
		Endpoint endpoint = new Endpoint(store);
		PostingSendCoordinator coordinator = new PostingSendCoordinator(drafts, binder -> endpoint);
		Binding failed = new Binding(); failed.accepted = false;
		coordinator.bind(new Host(), failed, "fixture-chan", "test", null); assertFalse(coordinator.canSend());
		coordinator.send(form(ReadCaptchaTask.CaptchaState.PASS), () -> { throw new AssertionError("unbound capture"); }, () -> null);
		coordinator.unbind(); coordinator.unbind(); assertEquals(1, failed.unbinds); assertEquals(0, store.postWrites);
		Binding inline = new Binding() {
			@Override public boolean bind(ServiceConnection connection) { super.bind(connection); connect(); return true; }
		};
		coordinator.bind(new Host(), inline, "fixture-chan", "test", null); assertTrue(coordinator.canSend());
		coordinator.unbind(); assertEquals(1, inline.unbinds); assertEquals(1, endpoint.unregisters);
		Binding throwing = new Binding() {
			@Override public boolean bind(ServiceConnection connection) { super.bind(connection); throw new SecurityException("fixture"); }
		};
		try { coordinator.bind(new Host(), throwing, "fixture-chan", "test", null); fail("Expected bind failure"); }
		catch (SecurityException expected) { assertFalse(coordinator.canSend()); }
		coordinator.unbind(); assertEquals(1, throwing.unbinds);
	}
}
