package com.mishiranu.dashchan.ui.posting;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.async.RolloverThreadTask;
import com.mishiranu.dashchan.content.model.AttachmentItem;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A retained preparation operation, not another sender. Rotation never starts a second download. */
public final class RolloverThreadDialog extends DialogFragment {
	private static final String TAG = "rollover_thread";
	private State state;
	private ProgressBar progress;
	private boolean handoffPosted;
	private final Runnable handoff = () -> {
		handoffPosted = false;
		performPreparedDraftHandoff();
	};

	public static boolean canOffer(Chan chan, PostItem post) {
		return post.isOriginalPost() && !post.isDeleted()
				&& chan.configuration.safe().obtainBoard(post.getBoardName()).allowPosting
				&& chan.configuration.safe().obtainPosting(post.getBoardName(), true) != null;
	}

	public static void show(FragmentManager manager, Chan chan, PostItem post) {
		if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null || !canOffer(chan, post)) return;
		Bundle args = new Bundle();
		args.putString("chan", chan.name); args.putString("board", post.getBoardName());
		args.putString("subject", post.getSubject()); args.putString("comment", post.getCommentMarkup(chan));
		ArrayList<String> uris = new ArrayList<>(), names = new ArrayList<>();
		List<AttachmentItem> attachments = post.getAttachmentItems();
		for (AttachmentItem item : attachments != null ? attachments : Collections.<AttachmentItem>emptyList()) {
			// Embedded players and external link previews are not uploadable original files.
			if (item.getGeneralType() != AttachmentItem.GeneralType.FILE) continue;
			android.net.Uri uri = item.hasOriginalFile() ? item.getFileUri(chan) : null;
			uris.add(uri != null ? uri.toString() : null);
			String name = item.getOriginalName();
			if (StringUtils.isEmpty(name)) name = item.getFileName(chan);
			names.add(StringUtils.isEmpty(name) ? "attachment" : name);
		}
		args.putStringArrayList("uris", uris); args.putStringArrayList("names", names);
		RolloverThreadDialog dialog = new RolloverThreadDialog(); dialog.setArguments(args); dialog.show(manager, TAG);
	}

	@Override public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		state = new ViewModelProvider(this).get(State.class);
		// A process restart cannot prove that an old draft replacement was authorized.
		if (!state.initialized && savedInstanceState != null) state.stale = true;
		state.initialize(requireArguments());
		state.changed.observe(this, ignored -> render());
	}

	@NonNull @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
		progress = new ProgressBar(requireContext());
		ThemeEngine.applyStyle(progress);
		progress.setIndeterminate(true);
		int padding = Math.round(16 * getResources().getDisplayMetrics().density);
		progress.setPadding(padding, padding, padding, padding);
		InterfaceAppearance.standardDialogLoading(progress);
		AlertDialog dialog = MotionDialogBuilder.standard(requireContext()).setTitle(R.string.rollover_thread)
				.setMessage(R.string.rollover_preparing).setView(progress).setPositiveButton(android.R.string.ok, null)
				.setNegativeButton(android.R.string.cancel, null).create();
		InterfaceAppearance.standardCustomDialogContent(dialog, progress);
		return dialog;
	}

	@Override public void onStart() {
		super.onStart();
		AlertDialog dialog = (AlertDialog) requireDialog();
		dialog.setCanceledOnTouchOutside(false);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			if (state.result == null) state.start(); else openPreparedDraft();
			render();
		});
		render();
	}

	@Override public void onResume() {
		super.onResume();
		if (!state.started && !state.requiresConfirmation && !state.stale) state.start();
		render();
	}

	private void render() {
		if (!(getDialog() instanceof AlertDialog) || !((AlertDialog) getDialog()).isShowing()) return;
		AlertDialog dialog = (AlertDialog) getDialog();
		Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
		if (positive == null) return;
		progress.setVisibility(state.started && state.result == null && !state.stale
				? android.view.View.VISIBLE : android.view.View.GONE);
		positive.setEnabled(false);
		if (state.stale || state.result != null && state.result.invalidated) {
			dialog.setMessage(getString(R.string.rollover_interrupted));
		} else if (state.result != null) {
			if (state.result.failed.isEmpty()) {
				if (isResumed()) openPreparedDraft();
			} else {
				dialog.setMessage(getString(R.string.rollover_missing_files,
						android.text.TextUtils.join("\n", state.result.failed)));
				positive.setText(R.string.rollover_continue); positive.setEnabled(true);
			}
		} else if (state.started) {
			dialog.setMessage(getString(R.string.rollover_preparing));
		} else {
			dialog.setMessage(getString(R.string.rollover_replace_draft));
			positive.setText(R.string.rollover_replace); positive.setEnabled(true);
		}
	}

	private void openPreparedDraft() {
		if (handoffPosted || !isResumed() || state.committed || state.stale
				|| state.result == null || state.result.invalidated) return;
		// onResume/LiveData delivery can run inside a FragmentManager transaction.
		// Leave that stack before synchronously removing the preparation dialog.
		handoffPosted = true;
		ConcurrentUtils.HANDLER.post(handoff);
	}

	private void performPreparedDraftHandoff() {
		if (!isAdded() || !isResumed() || state.committed || state.stale
				|| state.result == null || state.result.invalidated
				|| getDialog() == null || !getDialog().isShowing()) return;
		Context context = requireContext();
		if (getParentFragmentManager().isStateSaved()) return;
		if (!(requireActivity() instanceof MainActivity) || !state.authorized(context)) {
			state.stale = true; render(); return;
		}
		DraftsStorage storage = DraftsStorage.getInstance();
		// Consent covers exactly the old draft that was shown before preparation, not a newer edit.
		if (storage.getPostDraft(state.chan, state.board, null) != state.expectedDraft
				|| PostingService.isPostDraftQueued(state.expectedDraft)) {
			state.stale = true; render(); return;
		}
		ChanConfiguration.Posting posting = Chan.get(state.chan).configuration.safe().obtainPosting(state.board, true);
		if (posting == null || !Chan.get(state.chan).configuration.safe().obtainBoard(state.board).allowPosting
				|| state.result.attachments.size() > posting.attachmentCount) {
			state.stale = true; render(); return;
		}
		for (DraftsStorage.AttachmentDraft attachment : state.result.attachments) {
			if (storage.getAttachmentDraftFile(attachment.hash) == null) {
				state.stale = true; render(); return;
			}
		}
		DraftsStorage.PostDraft draft = createDraft(state.chan, state.board, state.subject, state.comment,
				state.result.attachments);
		storage.store(draft);
		state.committed = true;
		MainActivity activity = (MainActivity) requireActivity();
		String chan = state.chan, board = state.board;
		dismissBeforeNavigation(this, () -> activity.navigatePostingRollover(chan, board));
	}

	static void dismissBeforeNavigation(DialogFragment dialog, Runnable navigation) {
		// Navigation snapshots the source page and marks its child manager state saved.
		// A queued dismiss() is too late: remove the dialog now, before that snapshot.
		dialog.dismissNow();
		navigation.run();
	}

	/** Only OP content is copied, never its author identity, posting options or captcha. */
	static DraftsStorage.PostDraft createDraft(String chan, String board, String subject, String comment,
			ArrayList<DraftsStorage.AttachmentDraft> attachments) {
		return new DraftsStorage.PostDraft(chan, board, null, null, null, null, subject, comment, 0,
				new ArrayList<>(attachments), false, false, false, null);
	}

	public static final class State extends ViewModel {
		final MutableLiveData<Integer> changed = new MutableLiveData<>(0);
		boolean initialized, requiresConfirmation, started, stale, closed, committed;
		String chan, board, subject, comment;
		long generation;
		DraftsStorage.PostDraft expectedDraft;
		final ArrayList<RolloverThreadTask.Source> sources = new ArrayList<>();
		RolloverThreadTask task;
		RolloverThreadTask.Result result;
		final ArrayList<String> retained = new ArrayList<>();

		void initialize(Bundle args) {
			if (initialized) return;
			initialized = true;
			chan = args.getString("chan"); board = args.getString("board");
			subject = args.getString("subject"); comment = args.getString("comment");
			ArrayList<String> uris = args.getStringArrayList("uris"), names = args.getStringArrayList("names");
			if (uris == null || names == null || uris.size() != names.size()) { stale = true; return; }
			for (int i = 0; i < uris.size(); i++) sources.add(new RolloverThreadTask.Source(uris.get(i), names.get(i)));
			expectedDraft = DraftsStorage.getInstance().getPostDraft(chan, board, null);
			requiresConfirmation = expectedDraft != null && !expectedDraft.isEmpty();
			try { generation = ProfileGeneration.current(MainApplication.getInstance()); }
			catch (IOException e) { stale = true; }
		}

		boolean authorized(Context context) {
			return !SecurityStore.locked(context) && ProfileGeneration.canWrite(context, generation);
		}

		void start() {
			if (started || stale || closed) return;
			if (!authorized(MainApplication.getInstance())) { stale = true; return; }
			Chan current = Chan.get(chan);
			ChanConfiguration.Posting posting = current.configuration.safe().obtainPosting(board, true);
			if (posting == null) { stale = true; return; }
			if (PostingService.isPostDraftQueued(expectedDraft)) { stale = true; return; }
			started = true;
			task = new RolloverThreadTask(current, sources, posting.attachmentCount, generation, value -> {
				task = null;
				if (closed) { DraftsStorage.getInstance().discardPreparedAttachmentDrafts(value.attachments); return; }
				result = value;
				for (DraftsStorage.AttachmentDraft item : value.attachments) retained.add(item.hash);
				DraftsStorage.getInstance().retainAttachmentDrafts(retained);
				changed.setValue(changed.getValue() + 1);
			});
			try { task.execute(ConcurrentUtils.SEPARATE_EXECUTOR); }
			catch (RuntimeException e) { task.cancel(); task = null; stale = true; }
		}

		@Override protected void onCleared() {
			closed = true;
			if (task != null) task.cancel();
			if (!retained.isEmpty()) DraftsStorage.getInstance().releaseAttachmentDrafts(retained);
			retained.clear();
			if (!committed && result != null) DraftsStorage.getInstance().discardPreparedAttachmentDrafts(result.attachments);
		}
	}

	@Override public void onDestroyView() {
		ConcurrentUtils.HANDLER.removeCallbacks(handoff);
		handoffPosted = false;
		progress = null;
		super.onDestroyView();
	}
}
