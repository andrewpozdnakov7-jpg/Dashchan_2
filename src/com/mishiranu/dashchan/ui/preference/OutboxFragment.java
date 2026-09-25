package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.content.storage.OutboxState;
import com.mishiranu.dashchan.content.storage.OutboxStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.text.DateFormat;
import java.util.Date;

/** Explicit local recovery only: this screen never dispatches a POST. */
public class OutboxFragment extends PreferenceFragment {
	private int generation;
	private int limit = 50;
	private AlertDialog dialog;

	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refresh();
	}

	@Override public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.outbox_title), null);
	}

	@Override public void onDestroyView() {
		generation++;
		if (dialog != null) dialog.dismiss();
		dialog = null;
		super.onDestroyView();
	}

	private void refresh() {
		int current = ++generation;
		if (!Preferences.isOutboxJournalEnabled()) {
			removeAllPreferences();
			addButton(R.string.outbox_disabled, 0).setSelectable(false);
			return;
		}
		OutboxStorage.getInstance().list(entries -> {
			if (getView() == null || current != generation) return;
			removeAllPreferences();
			addButton(R.string.outbox_summary, 0).setSelectable(false);
			addButton(R.string.outbox_refresh, 0).setOnClickListener(p -> refresh());
			if (entries.isEmpty()) addButton(R.string.outbox_empty, 0).setSelectable(false);
			for (int i = 0; i < Math.min(limit, entries.size()); i++) {
				OutboxStorage.Entry entry = entries.get(i);
				String title = entry.chanName + " /" + (entry.boardName != null ? entry.boardName : "") + "/"
						+ (entry.threadNumber != null ? " #" + entry.threadNumber : "");
				String summary = getString(stateTitle(entry.state)) + " · " +
						DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.updated));
				addButton(title, summary).setOnClickListener(p -> showEntry(entry, title));
			}
			if (limit < entries.size()) addButton(R.string.outbox_more, 0).setOnClickListener(p -> {
				limit += 50;
				refresh();
			});
		}, error -> showFailure());
	}

	private void showEntry(OutboxStorage.Entry entry, String title) {
		AlertDialog.Builder builder = new AlertDialog.Builder(requireContext()).setTitle(title)
				.setMessage(getString(stateTitle(entry.state)) + "\n\n" + getString(
						entry.state == OutboxState.UNKNOWN_RESULT ? R.string.outbox_unknown_summary : R.string.outbox_summary))
				.setNegativeButton(android.R.string.cancel, null);
		if (entry.state != OutboxState.SENT && !entry.state.isActive()) {
			builder.setPositiveButton(R.string.outbox_restore, (d, which) -> confirmRestore(entry));
			if (entry.threadNumber != null) builder.setNegativeButton(R.string.outbox_open_thread,
					(d, which) -> startActivity(destination(entry, false)));
		} else if (entry.threadNumber != null) {
			builder.setPositiveButton(R.string.outbox_open_thread, (d, which) -> startActivity(destination(entry, false)));
		}
		if (!entry.state.isActive()) builder.setNeutralButton(R.string.outbox_delete, (d, which) -> {
			dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.outbox_delete)
					.setMessage(R.string.outbox_delete_summary).setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok, (confirmation, button) ->
							OutboxStorage.getInstance().delete(entry.id, () -> {
								if (getView() != null) refresh();
							}, error -> showFailure())).show();
		});
		dialog = builder.show();
	}

	private Intent destination(OutboxStorage.Entry entry, boolean posting) {
		return new Intent(requireContext(), MainActivity.class).setAction(posting ? C.ACTION_POSTING : "outbox:" + entry.id)
				.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
				.putExtra(C.EXTRA_CHAN_NAME, entry.chanName).putExtra(C.EXTRA_BOARD_NAME, entry.boardName)
				.putExtra(C.EXTRA_THREAD_NUMBER, entry.threadNumber).putExtra(C.EXTRA_POST_NUMBER, entry.postNumber);
	}

	private void confirmRestore(OutboxStorage.Entry entry) {
		dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.outbox_restore)
				.setMessage(R.string.outbox_restore_summary).setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (d, which) -> restore(entry, false)).show();
	}

	private void restore(OutboxStorage.Entry entry, boolean textOnly) {
		int current = generation;
		// Initialize the existing draft store on its owning/UI thread, before the copy worker.
		DraftsStorage drafts = DraftsStorage.getInstance();
		OutboxStorage.getInstance().restore(entry.id, textOnly, draft -> {
			if (getView() == null || current != generation) return;
			drafts.store(draft);
			drafts.await(true);
			startActivity(destination(entry, true));
		}, error -> {
			if (getView() == null || current != generation) return;
			if (!textOnly && error instanceof OutboxStorage.AttachmentRecoveryException) {
				dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.outbox_restore_text)
						.setMessage(R.string.outbox_restore_text_summary)
						.setNegativeButton(android.R.string.cancel, null)
						.setPositiveButton(android.R.string.ok, (d, which) -> restore(entry, true)).show();
			} else showFailure();
		});
	}

	private void showFailure() {
		if (getView() != null) ClickableToast.show(R.string.outbox_operation_failed);
	}

	private static int stateTitle(OutboxState state) {
		switch (state) {
			case PREPARING: return R.string.outbox_preparing;
			case READY: return R.string.outbox_ready;
			case SENDING: return R.string.outbox_sending;
			case WAITING: return R.string.outbox_waiting;
			case SENT: return R.string.outbox_sent;
			case NEEDS_CAPTCHA: return R.string.outbox_captcha;
			case NEEDS_LOGIN: return R.string.outbox_login;
			case FAILED: return R.string.outbox_failed;
			case UNKNOWN_RESULT: return R.string.outbox_unknown;
			default: return R.string.outbox_interrupted;
		}
	}
}
