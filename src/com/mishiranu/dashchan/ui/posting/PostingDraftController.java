package com.mishiranu.dashchan.ui.posting;

import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import java.util.ArrayList;
import java.util.function.Supplier;

/** Persistence owner. No retained UI/Fragment/attachment controller/captcha controller references. */
final class PostingDraftController {
	interface Store {
		DraftsStorage.PostDraft getPostDraft(String chan, String board, String thread);
		boolean isPostDraftQueued(DraftsStorage.PostDraft draft);
		DraftsStorage.PostDraft restoreFailedPostDraft(String chan, String board, String thread);
		DraftsStorage.CaptchaDraft getCaptchaDraft(String chan);
		ArrayList<DraftsStorage.AttachmentDraft> getFutureAttachmentDrafts();
		void consumeFutureAttachmentDrafts();
		void storePost(DraftsStorage.PostDraft draft);
		void storeCaptcha(String chan, DraftsStorage.CaptchaDraft draft);
	}
	private final Store store;
	private boolean draftSaved;
	PostingDraftController() { this(new Store() {
		@Override public DraftsStorage.PostDraft getPostDraft(String chan, String board, String thread) {
			return DraftsStorage.getInstance().getPostDraft(chan, board, thread);
		}
		@Override public boolean isPostDraftQueued(DraftsStorage.PostDraft draft) { return PostingService.isPostDraftQueued(draft); }
		@Override public DraftsStorage.PostDraft restoreFailedPostDraft(String chan, String board, String thread) {
			return PostingService.restoreFailedPostDraft(chan, board, thread);
		}
		@Override public DraftsStorage.CaptchaDraft getCaptchaDraft(String chan) { return DraftsStorage.getInstance().getCaptchaDraft(chan); }
		@Override public ArrayList<DraftsStorage.AttachmentDraft> getFutureAttachmentDrafts() { return DraftsStorage.getInstance().getFutureAttachmentDrafts(); }
		@Override public void consumeFutureAttachmentDrafts() { DraftsStorage.getInstance().consumeFutureAttachmentDrafts(); }
		@Override public void storePost(DraftsStorage.PostDraft draft) { DraftsStorage.getInstance().store(draft); }
		@Override public void storeCaptcha(String chan, DraftsStorage.CaptchaDraft draft) { DraftsStorage.getInstance().store(chan, draft); }
	}); }
	PostingDraftController(Store store) { this.store = store; }

	DraftsStorage.PostDraft obtainPostDraft(String chan, String board, String thread, String name, String email,
			String password, String subject, String comment, int cursor, ArrayList<DraftsStorage.AttachmentDraft> attachments,
			boolean sage, boolean spoiler, boolean originalPoster, String icon) {
		return new DraftsStorage.PostDraft(chan, board, thread, name, email, password, subject, comment, cursor,
				attachments, sage, spoiler, originalPoster, icon);
	}
	DraftsStorage.PostDraft restorePostDraft(String chan, String board, String thread) {
		DraftsStorage.PostDraft draft = store.getPostDraft(chan, board, thread);
		if (draft == null || store.isPostDraftQueued(draft)) {
			DraftsStorage.PostDraft failed = store.restoreFailedPostDraft(chan, board, thread);
			draft = failed != null ? failed : null;
		}
		return draft;
	}
	DraftsStorage.CaptchaDraft getCaptchaDraft(String chan) { return store.getCaptchaDraft(chan); }
	ArrayList<DraftsStorage.AttachmentDraft> getFutureAttachmentDrafts() { return store.getFutureAttachmentDrafts(); }
	void consumeFutureAttachmentDrafts() { store.consumeFutureAttachmentDrafts(); }
	void storePostDraft(DraftsStorage.PostDraft postDraft) { store.storePost(postDraft); }
	void resetSavedGuard() { draftSaved = false; }

	void saveDraft(boolean sendSuccess, String chan, Supplier<DraftsStorage.PostDraft> post,
			Supplier<DraftsStorage.CaptchaDraft> captcha) {
		if (!sendSuccess && !draftSaved) {
			draftSaved = true;
			store.storePost(post.get());
			store.storeCaptcha(chan, captcha.get());
		}
	}
	void storeForSend(DraftsStorage.PostDraft postDraft, String chan, Supplier<DraftsStorage.CaptchaDraft> captcha) {
		store.storePost(postDraft);
		store.storeCaptcha(chan, captcha.get());
		draftSaved = true;
	}
}
