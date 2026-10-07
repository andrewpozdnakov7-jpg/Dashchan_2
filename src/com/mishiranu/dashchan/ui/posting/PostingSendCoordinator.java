package com.mishiranu.dashchan.ui.posting;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import chan.content.ChanPerformer;
import com.mishiranu.dashchan.content.async.SendPostTask;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/** Main-thread service/send owner. Host and binding context exist only during the Fragment view lifetime. */
final class PostingSendCoordinator {
	interface Host {
		boolean isResumed();
		void showPostingState(boolean allowDialog, boolean progressMode, SendPostTask.ProgressState state,
				int attachmentIndex, int attachmentsCount);
		void updatePostingProgress(long progress, long progressMax);
		void dismissPostingProgress();
		void forgetPostingProgress();
		void setSendButtonEnabled(boolean enabled);
		void closePostingFragment();
		void showPostingFailure(PostingService.FailResult failure);
		void refreshCaptcha(boolean mayShowLoadButton);
		void updatePostingConfigurationIfNeeded();
	}

	/** Narrow service seams allow lifecycle tests without a real service or network. */
	interface Binding {
		boolean bind(ServiceConnection connection);
		void unbind(ServiceConnection connection);
	}
	interface Endpoint {
		void register(PostingService.Callback callback, String chan, String board, String thread);
		void unregister(PostingService.Callback callback);
		boolean send(String chan, ChanPerformer.SendPostData data, DraftsStorage.PostDraft draft,
				Collection<String> hashes, boolean allowFloodRetry);
		void cancel(String chan, String board, String thread);
	}

	private final PostingDraftController drafts;
	private final Function<IBinder, Endpoint> endpoints;
	private Host host;
	private Binding binding;
	private boolean bound;
	private Endpoint postingBinder;
	private ServiceConnection postingConnection;
	private PostingService.Callback postingCallback;
	private long bindingGeneration;
	private String chanName, boardName, threadNumber;
	private boolean allowDialog = true;
	private boolean sendSuccess;
	private PostingService.FailResult failResult;

	PostingSendCoordinator(PostingDraftController drafts) {
		this(drafts, binder -> new AndroidEndpoint((PostingService.Binder) binder));
	}
	PostingSendCoordinator(PostingDraftController drafts, Function<IBinder, Endpoint> endpoints) {
		this.drafts = drafts;
		this.endpoints = endpoints;
	}

	void bind(Host host, Context context, String chan, String board, String thread) {
		bind(host, new Binding() {
			@Override public boolean bind(ServiceConnection connection) {
				return context.bindService(new Intent(context, PostingService.class), connection, Context.BIND_AUTO_CREATE);
			}
			@Override public void unbind(ServiceConnection connection) { context.unbindService(connection); }
		}, chan, board, thread);
	}
	void bind(Host host, Binding binding, String chan, String board, String thread) {
		if (this.binding != null) return;
		this.host = host;
		this.binding = binding;
		chanName = chan;
		boardName = board;
		threadNumber = thread;
		long generation = ++bindingGeneration;
		postingCallback = createCallback(generation);
		PostingService.Callback callback = postingCallback;
		postingConnection = new ServiceConnection() {
			@Override public void onServiceConnected(ComponentName name, IBinder service) {
				if (!isCurrentBinding(generation)) return;
				releaseEndpoint();
				postingBinder = endpoints.apply(service);
				postingBinder.register(callback, chanName, boardName, threadNumber);
			}
			@Override public void onServiceDisconnected(ComponentName name) {
				if (isCurrentBinding(generation)) releaseEndpoint();
			}
		};
		// Android connects asynchronously; an injected binding may connect inline.
		bound = true;
		try {
			bound = binding.bind(postingConnection);
		} catch (RuntimeException e) {
			bound = false;
			releaseEndpoint();
			// Context requires release even when bindService throws or returns false.
			try {
				binding.unbind(postingConnection);
			} catch (RuntimeException cleanupError) {
				e.addSuppressed(cleanupError);
			}
			this.binding = null;
			this.host = null;
			postingConnection = null;
			postingCallback = null;
			bindingGeneration++;
			throw e;
		}
		if (!bound) releaseEndpoint();
	}
	private boolean isCurrentBinding(long generation) {
		return bound && binding != null && host != null && generation == bindingGeneration;
	}
	private void releaseEndpoint() {
		if (postingBinder != null) {
			postingBinder.unregister(postingCallback);
			postingBinder = null;
		}
	}
	void unbind() {
		releaseEndpoint();
		Binding previous = binding;
		ServiceConnection connection = postingConnection;
		binding = null;
		bound = false;
		postingConnection = null;
		postingCallback = null;
		bindingGeneration++;
		if (previous != null) previous.unbind(connection);
		dismiss();
		host = null;
	}
	boolean canSend() { return postingBinder != null && host != null; }
	boolean isSendSuccess() { return sendSuccess; }

	void send(PostingFormData form, Supplier<DraftsStorage.PostDraft> post, Supplier<DraftsStorage.CaptchaDraft> captcha) {
		if (!canSend()) return;
		ChanPerformer.SendPostData request = form.createRequest(boardName, threadNumber);
		DraftsStorage.PostDraft draft = post.get();
		drafts.storeForSend(draft, chanName, captcha);
		allowDialog = false;
		if (postingBinder.send(chanName, request, draft, form.createAttachmentHashes(), form.allowFloodRetry())) {
			host.setSendButtonEnabled(false);
			host.dismissPostingProgress();
			minimize();
		} else {
			allowDialog = true;
			drafts.resetSavedGuard();
		}
	}
	void cancel() {
		if (host != null) host.forgetPostingProgress();
		if (postingBinder != null) postingBinder.cancel(chanName, boardName, threadNumber);
	}
	void minimize() {
		if (host != null) {
			host.forgetPostingProgress();
			host.closePostingFragment();
		}
	}
	void dismiss() {
		if (host != null) {
			host.dismissPostingProgress();
			host.setSendButtonEnabled(true);
		}
	}

	private PostingService.Callback createCallback(long generation) {
		return new PostingService.Callback() {
			@Override public void onState(boolean progressMode, SendPostTask.ProgressState state,
					int attachmentIndex, int attachmentsCount) {
				if (isCurrentBinding(generation)) host.showPostingState(allowDialog, progressMode, state, attachmentIndex, attachmentsCount);
			}
			@Override public void onProgress(long progress, long progressMax) {
				if (isCurrentBinding(generation)) host.updatePostingProgress(progress, progressMax);
			}
			@Override public void onStop(boolean success) {
				if (!isCurrentBinding(generation)) return;
				dismiss();
				if (success) {
					sendSuccess = true;
					if (host.isResumed()) host.closePostingFragment();
				}
			}
		};
	}
	void handleFailResult(PostingService.FailResult failure) {
		if (host != null && host.isResumed()) {
			host.showPostingFailure(failure);
			if (failure.errorItem.httpResponseCode == 0 && !failure.keepCaptcha) host.refreshCaptcha(!failure.captchaError);
			host.updatePostingConfigurationIfNeeded();
		} else {
			failResult = failure;
		}
	}
	void deliverPendingUiEvents() {
		PostingService.FailResult pending = failResult;
		failResult = null;
		if (pending != null) handleFailResult(pending);
	}

	private static final class AndroidEndpoint implements Endpoint {
		private final PostingService.Binder binder;
		AndroidEndpoint(PostingService.Binder binder) { this.binder = binder; }
		@Override public void register(PostingService.Callback callback, String chan, String board, String thread) { binder.register(callback, chan, board, thread); }
		@Override public void unregister(PostingService.Callback callback) { binder.unregister(callback); }
		@Override public boolean send(String chan, ChanPerformer.SendPostData data, DraftsStorage.PostDraft draft,
				Collection<String> hashes, boolean allowFloodRetry) { return binder.executeSendPost(chan, data, draft, hashes, allowFloodRetry); }
		@Override public void cancel(String chan, String board, String thread) { binder.cancelSendPost(chan, board, thread); }
	}
}
