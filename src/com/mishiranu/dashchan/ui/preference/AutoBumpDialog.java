package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.database.PagesDatabase;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.service.AutoBumpWorker;
import com.mishiranu.dashchan.content.storage.AutoBumpStorage;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoBumpDialog extends DialogFragment {
	private static final String EXTRA_TASK_ID = "taskId";
	private static final String EXTRA_BOARD_NAME = "boardName";
	private static final String EXTRA_THREAD_NUMBER = "threadNumber";
	private static final String EXTRA_TITLE = "title";
	private static final Pattern THREAD_PATH_PATTERN = Pattern.compile(
			"(?i)/([a-z0-9_]+)/(?:(?:res|thread)/)?(\\d+)(?:\\.html)?/?");

	private EditText threadEdit;
	private EditText messageEdit;
	private EditText numberEdit;
	private EditText intervalEdit;
	private CheckBox enabledCheck;
	private CheckBox sendNowCheck;

	public static AutoBumpDialog newTask(String boardName, String threadNumber, String title) {
		AutoBumpDialog dialog = new AutoBumpDialog();
		Bundle args = new Bundle();
		args.putString(EXTRA_BOARD_NAME, boardName);
		args.putString(EXTRA_THREAD_NUMBER, threadNumber);
		args.putString(EXTRA_TITLE, title);
		dialog.setArguments(args);
		return dialog;
	}

	public static AutoBumpDialog editTask(String taskId) {
		AutoBumpDialog dialog = new AutoBumpDialog();
		Bundle args = new Bundle();
		args.putString(EXTRA_TASK_ID, taskId);
		dialog.setArguments(args);
		return dialog;
	}

	@Override
	@NonNull
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Context context = requireContext();
		Bundle args = requireArguments();
		String taskId = args.getString(EXTRA_TASK_ID);
		AutoBumpStorage.Task existing = taskId != null
				? AutoBumpStorage.getInstance().getTask(taskId) : null;

		float density = ResourceUtils.obtainDensity(context);
		int padding = Math.round(24f * density);
		int smallGap = Math.round(8f * density);
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(padding, smallGap, padding, 0);

		threadEdit = addEdit(layout, R.string.auto_bump_thread,
				InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		messageEdit = addEdit(layout, R.string.auto_bump_message,
				InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
						| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		messageEdit.setMinLines(2);
		TextView hint = new TextView(context);
		hint.setText(R.string.auto_bump_message__hint);
		layout.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		numberEdit = addLabeledEdit(layout, getString(R.string.auto_bump_next_number,
				AutoBumpStorage.MAX_NEXT_NUMBER), InputType.TYPE_CLASS_NUMBER, smallGap);
		intervalEdit = addLabeledEdit(layout, getString(R.string.auto_bump_interval_minutes,
				AutoBumpStorage.MIN_INTERVAL_MINUTES), InputType.TYPE_CLASS_NUMBER, smallGap);
		enabledCheck = new CheckBox(context);
		enabledCheck.setText(R.string.auto_bump_task_enabled);
		layout.addView(enabledCheck);
		sendNowCheck = new CheckBox(context);
		sendNowCheck.setText(R.string.auto_bump_send_now);
		layout.addView(sendNowCheck);

		if (existing != null) {
			threadEdit.setText("/" + existing.boardName + "/res/" + existing.threadNumber);
			messageEdit.setText(existing.message);
			numberEdit.setText(Long.toString(existing.nextNumber));
			intervalEdit.setText(Long.toString(existing.intervalMinutes));
			enabledCheck.setChecked(existing.enabled);
		} else {
			String boardName = args.getString(EXTRA_BOARD_NAME);
			String threadNumber = args.getString(EXTRA_THREAD_NUMBER);
			if (!StringUtils.isEmpty(boardName) && !StringUtils.isEmpty(threadNumber)) {
				threadEdit.setText("/" + boardName + "/res/" + threadNumber);
			}
			numberEdit.setText("1");
			intervalEdit.setText(Long.toString(AutoBumpStorage.MIN_INTERVAL_MINUTES));
			enabledCheck.setChecked(true);
		}

		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(true);
		scrollView.addView(layout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));

		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(existing != null ? R.string.manage_auto_bump : R.string.add_auto_bump_task)
				.setView(scrollView)
				.setPositiveButton(android.R.string.ok, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		if (existing != null) {
			dialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.auto_bump_delete_task),
					(d, which) -> {});
		}
		dialog.setOnShowListener(ignored -> {
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> save(existing));
			if (existing != null) {
				dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
					AutoBumpStorage.getInstance().remove(existing.id);
					AutoBumpWorker.scheduleNext(context);
					notifyManager();
					dismiss();
				});
			}
		});
		return dialog;
	}

	private EditText addEdit(LinearLayout layout, int hintResId, int inputType) {
		EditText editText = new EditText(layout.getContext());
		if (hintResId != 0) {
			editText.setHint(hintResId);
		}
		editText.setInputType(inputType);
		layout.addView(editText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return editText;
	}

	private EditText addLabeledEdit(LinearLayout layout, CharSequence labelText, int inputType, int topMargin) {
		TextView label = new TextView(layout.getContext());
		label.setText(labelText);
		label.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
		LinearLayout.LayoutParams labelLayoutParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		labelLayoutParams.topMargin = topMargin;
		layout.addView(label, labelLayoutParams);
		return addEdit(layout, 0, inputType);
	}

	private void save(AutoBumpStorage.Task existing) {
		Chan chan = Chan.get("dvach");
		List<String> pass = Preferences.getCaptchaPass(chan);
		if (!Preferences.checkHasMultipleValues(pass)) {
			ClickableToast.show(R.string.auto_bump_pass_required);
			return;
		}
		String threadValue = threadEdit.getText().toString().trim();
		Uri threadUri = Uri.parse(threadValue);
		if (threadUri.isRelative() && threadUri.getAuthority() != null) {
			ClickableToast.show(R.string.auto_bump_invalid_thread);
			return;
		}
		if (!threadUri.isRelative()) {
			String scheme = threadUri.getScheme();
			Chan uriChan = Chan.getPreferred(null, threadUri);
			if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
					|| !"dvach".equals(uriChan.name)) {
				ClickableToast.show(R.string.auto_bump_invalid_thread);
				return;
			}
		}
		String threadPath = threadUri.getPath();
		if (threadPath == null) {
			ClickableToast.show(R.string.auto_bump_invalid_thread);
			return;
		}
		Matcher matcher = THREAD_PATH_PATTERN.matcher(threadPath);
		if (!matcher.matches()) {
			ClickableToast.show(R.string.auto_bump_invalid_thread);
			return;
		}
		String message = messageEdit.getText().toString().trim();
		if (message.isEmpty()) {
			ClickableToast.show(R.string.auto_bump_empty_message);
			return;
		}
		long nextNumber;
		long interval;
		try {
			nextNumber = Math.max(1L, Long.parseLong(numberEdit.getText().toString()));
			interval = Long.parseLong(intervalEdit.getText().toString());
		} catch (NumberFormatException e) {
			ClickableToast.show(R.string.enter_valid_data);
			return;
		}
		if (nextNumber > AutoBumpStorage.MAX_NEXT_NUMBER) {
			ClickableToast.show(getString(R.string.auto_bump_counter_range__format,
					AutoBumpStorage.MAX_NEXT_NUMBER));
			return;
		}
		if (interval < AutoBumpStorage.MIN_INTERVAL_MINUTES
				|| interval > AutoBumpStorage.MAX_INTERVAL_MINUTES) {
			ClickableToast.show(getString(R.string.auto_bump_interval_range__format,
					AutoBumpStorage.MIN_INTERVAL_MINUTES, AutoBumpStorage.MAX_INTERVAL_MINUTES));
			return;
		}
		AutoBumpStorage.Task task = existing != null ? existing : new AutoBumpStorage.Task();
		task.chanName = "dvach";
		task.boardName = matcher.group(1);
		task.threadNumber = matcher.group(2);
		String title = requireArguments().getString(EXTRA_TITLE);
		if (existing == null || title != null) {
			task.title = title;
		}
		task.message = message;
		task.nextNumber = nextNumber;
		task.intervalMinutes = interval;
		task.enabled = enabledCheck.isChecked();
		task.completed = existing != null && existing.completed && !task.enabled
				&& task.nextNumber >= AutoBumpStorage.MAX_NEXT_NUMBER;
		task.consecutiveFailures = 0;
		task.lastError = null;
		long now = System.currentTimeMillis();
		task.lastActivityAt = now;
		PostNumber lastPostNumber = PagesDatabase.getInstance().getLastExistingPostNumber(
				new PagesDatabase.ThreadKey(task.chanName, task.boardName, task.threadNumber));
		task.lastBumpPostNumber = lastPostNumber != null ? lastPostNumber.toString() : null;
		task.nextRunAt = sendNowCheck.isChecked() && task.enabled ? now
				: AutoBumpStorage.calculateNextRunAt(now, interval);
		if (!AutoBumpStorage.getInstance().put(task)) {
			ClickableToast.show(R.string.auto_bump_task_limit);
			return;
		}
		if (sendNowCheck.isChecked() && task.enabled) {
			AutoBumpStorage.getInstance().scheduleNow(task.id);
		}
		AutoBumpWorker.scheduleNext(requireContext());
		ClickableToast.show(R.string.auto_bump_task_saved);
		notifyManager();
		dismiss();
	}

	private void notifyManager() {
		if (getParentFragment() instanceof AutoBumpFragment) {
			((AutoBumpFragment) getParentFragment()).refreshPreferences();
		}
	}
}
