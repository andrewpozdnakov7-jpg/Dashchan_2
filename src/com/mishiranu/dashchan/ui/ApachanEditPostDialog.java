package com.mishiranu.dashchan.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.apachan.ApachanChanPerformer;
import com.mishiranu.dashchan.content.async.ReadApachanEditPostTask;
import com.mishiranu.dashchan.content.async.SendApachanEditPostTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;

public final class ApachanEditPostDialog {
	public static final String RESULT_KEY = "apachanEditPostResult";
	public static final String RESULT_CHAN_NAME = "chanName";
	public static final String RESULT_BOARD_NAME = "boardName";
	public static final String RESULT_THREAD_NUMBER = "threadNumber";

	private static final String EXTRA_CHAN_NAME = "chanName";
	private static final String EXTRA_BOARD_NAME = "boardName";
	private static final String EXTRA_THREAD_NUMBER = "threadNumber";
	private static final String EXTRA_POST_NUMBER = "postNumber";
	private static final String EXTRA_SUBJECT = "subject";
	private static final String EXTRA_COMMENT = "comment";
	private static final String EXTRA_ORIGINAL_POST = "originalPost";
	private static final String EXTRA_HAS_ATTACHMENT = "hasAttachment";
	private static final String EXTRA_DELETE_ATTACHMENT = "deleteAttachment";
	private static final String EXTRA_SHOW_ORIGINAL_POSTER = "showOriginalPoster";
	private static final String EXTRA_COMMENT_LIMIT = "commentLimit";

	private static final String TAG_LOAD = "apachanEditPostLoad";
	private static final String TAG_EDIT = "apachanEditPostEdit";
	private static final String TAG_SEND = "apachanEditPostSend";

	private ApachanEditPostDialog() {}

	public static void show(FragmentManager fragmentManager, String chanName, String boardName,
			String threadNumber, String postNumber) {
		if (!fragmentManager.isStateSaved()) {
			new LoadDialog(chanName, boardName, threadNumber, postNumber).show(fragmentManager, TAG_LOAD);
		}
	}

	private static Bundle createBaseArguments(String chanName, String boardName,
			String threadNumber, String postNumber) {
		Bundle args = new Bundle();
		args.putString(EXTRA_CHAN_NAME, chanName);
		args.putString(EXTRA_BOARD_NAME, boardName);
		args.putString(EXTRA_THREAD_NUMBER, threadNumber);
		args.putString(EXTRA_POST_NUMBER, postNumber);
		return args;
	}

	public static class LoadViewModel extends TaskViewModel<ReadApachanEditPostTask,
			ReadApachanEditPostTask.Result> {}

	public static class LoadDialog extends DialogFragment {
		public LoadDialog() {}

		private LoadDialog(String chanName, String boardName, String threadNumber, String postNumber) {
			setArguments(createBaseArguments(chanName, boardName, threadNumber, postNumber));
		}

		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = new ProgressDialog(requireContext(), null);
			dialog.setMessage(getString(R.string.loading__ellipsis));
			return dialog;
		}

		@Override
		public void onActivityCreated(Bundle savedInstanceState) {
			super.onActivityCreated(savedInstanceState);
			Bundle args = requireArguments();
			LoadViewModel viewModel = new ViewModelProvider(this).get(LoadViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				Chan chan = Chan.get(args.getString(EXTRA_CHAN_NAME));
				ReadApachanEditPostTask task = new ReadApachanEditPostTask(viewModel, chan,
						args.getString(EXTRA_POST_NUMBER));
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismiss();
				if (result.editPostResult != null) {
					EditDialog dialog = new EditDialog(args, result.editPostResult);
					if (!getParentFragmentManager().isStateSaved()) {
						dialog.show(getParentFragmentManager(), TAG_EDIT);
					}
				} else {
					ClickableToast.show(result.errorItem);
				}
			});
		}
	}

	public static class EditDialog extends DialogFragment {
		private EditText subjectView;
		private EditText commentView;
		private CheckBox deleteAttachmentView;

		public EditDialog() {}

		private EditDialog(Bundle baseArguments, ApachanChanPerformer.EditPostResult result) {
			Bundle args = new Bundle(baseArguments);
			args.putString(EXTRA_SUBJECT, result.subject);
			args.putString(EXTRA_COMMENT, result.comment);
			args.putBoolean(EXTRA_ORIGINAL_POST, result.originalPost);
			args.putBoolean(EXTRA_HAS_ATTACHMENT, result.hasAttachment);
			args.putBoolean(EXTRA_SHOW_ORIGINAL_POSTER, result.showOriginalPoster);
			args.putInt(EXTRA_COMMENT_LIMIT, result.commentLimit);
			setArguments(args);
		}

		private EditDialog(Bundle sendArguments) {
			setArguments(new Bundle(sendArguments));
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			if (subjectView != null) outState.putString(EXTRA_SUBJECT, subjectView.getText().toString());
			if (commentView != null) outState.putString(EXTRA_COMMENT, commentView.getText().toString());
			if (deleteAttachmentView != null) {
				outState.putBoolean(EXTRA_DELETE_ATTACHMENT, deleteAttachmentView.isChecked());
			}
			super.onSaveInstanceState(outState);
		}

		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			Bundle args = requireArguments();
			float density = ResourceUtils.obtainDensity(requireContext());
			int padding = (int) (20f * density);
			LinearLayout root = new LinearLayout(requireContext());
			root.setOrientation(LinearLayout.VERTICAL);
			root.setPadding(padding, (int) (8f * density), padding, 0);

			subjectView = new EditText(requireContext());
			subjectView.setHint(R.string.subject);
			subjectView.setSingleLine(true);
			subjectView.setText(savedInstanceState != null ? savedInstanceState.getString(EXTRA_SUBJECT)
					: args.getString(EXTRA_SUBJECT));
			root.addView(subjectView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

			commentView = new EditText(requireContext());
			commentView.setHint(R.string.comment);
			commentView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
					| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
			commentView.setMinLines(6);
			commentView.setGravity(android.view.Gravity.TOP);
			commentView.setText(savedInstanceState != null ? savedInstanceState.getString(EXTRA_COMMENT)
					: args.getString(EXTRA_COMMENT));
			root.addView(commentView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

			if (args.getBoolean(EXTRA_HAS_ATTACHMENT) && !args.getBoolean(EXTRA_ORIGINAL_POST)) {
				deleteAttachmentView = new CheckBox(requireContext());
				deleteAttachmentView.setText(R.string.delete_attachment);
				deleteAttachmentView.setChecked(savedInstanceState != null
						? savedInstanceState.getBoolean(EXTRA_DELETE_ATTACHMENT)
						: args.getBoolean(EXTRA_DELETE_ATTACHMENT));
				root.addView(deleteAttachmentView, ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT);
			}

			ScrollView scrollView = new ScrollView(requireContext());
			scrollView.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.edit_post)
					.setView(scrollView).setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.save, null).create();
			dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setOnClickListener(button -> send()));
			return dialog;
		}

		private void send() {
			String comment = commentView.getText().toString().trim();
			if (StringUtils.isEmpty(comment)) {
				ClickableToast.show(R.string.comment_is_empty);
				return;
			}
			Bundle args = new Bundle(requireArguments());
			args.putString(EXTRA_SUBJECT, subjectView.getText().toString().trim());
			args.putString(EXTRA_COMMENT, comment);
			args.putBoolean(EXTRA_DELETE_ATTACHMENT,
					deleteAttachmentView != null && deleteAttachmentView.isChecked());
			dismiss();
			if (!getParentFragmentManager().isStateSaved()) {
				new SendDialog(args).show(getParentFragmentManager(), TAG_SEND);
			}
		}
	}

	public static class SendViewModel extends TaskViewModel<SendApachanEditPostTask,
			SendApachanEditPostTask.Result> {}

	public static class SendDialog extends DialogFragment {
		public SendDialog() {}

		private SendDialog(Bundle args) {
			setArguments(new Bundle(args));
		}

		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = new ProgressDialog(requireContext(), null);
			dialog.setMessage(getString(R.string.sending__ellipsis));
			return dialog;
		}

		@Override
		public void onActivityCreated(Bundle savedInstanceState) {
			super.onActivityCreated(savedInstanceState);
			Bundle args = requireArguments();
			SendViewModel viewModel = new ViewModelProvider(this).get(SendViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				Chan chan = Chan.get(args.getString(EXTRA_CHAN_NAME));
				SendApachanEditPostTask task = new SendApachanEditPostTask(viewModel, chan,
						args.getString(EXTRA_THREAD_NUMBER), args.getString(EXTRA_POST_NUMBER),
						args.getString(EXTRA_SUBJECT), args.getString(EXTRA_COMMENT),
						args.getBoolean(EXTRA_ORIGINAL_POST), args.getBoolean(EXTRA_DELETE_ATTACHMENT),
						args.getBoolean(EXTRA_SHOW_ORIGINAL_POSTER), args.getInt(EXTRA_COMMENT_LIMIT, 10));
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismiss();
				if (result.success) {
					Bundle bundle = new Bundle();
					bundle.putString(RESULT_CHAN_NAME, args.getString(EXTRA_CHAN_NAME));
					bundle.putString(RESULT_BOARD_NAME, args.getString(EXTRA_BOARD_NAME));
					bundle.putString(RESULT_THREAD_NUMBER, args.getString(EXTRA_THREAD_NUMBER));
					getParentFragmentManager().setFragmentResult(RESULT_KEY, bundle);
					ClickableToast.show(R.string.post_edited);
				} else {
					ClickableToast.show(result.errorItem);
					if (!getParentFragmentManager().isStateSaved()) {
						new EditDialog(args).show(getParentFragmentManager(), TAG_EDIT);
					}
				}
			});
		}
	}
}
