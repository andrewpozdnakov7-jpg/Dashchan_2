package com.mishiranu.dashchan.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;

/** The experimental posting form is not included in this distribution. */
public final class PostingFormAppearance {
	public static boolean isEnabled() { return false; }
	public PostingFormAppearance(View root) {}
	public void setupEditor(LinearLayout container, EditText input, LinearLayout formatting) {}
	public void setAttachmentAction(Runnable action) {}
	public void setAttachmentAvailable(boolean available) {}
	public void syncOptionsVisibility() {}
	public void decorateFields(EditText... inputs) {}
	public static boolean fieldError(EditText view, boolean error) { return false; }
	public static void syncFieldVisibility(EditText... views) {}
	public static void refreshView(View view) {}
	public static void captchaStateChanged(View view) {}
	public static void captchaStateChanged(View view, boolean accepted) {}
	public static void captchaStateChanged(View view, com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType type,
			com.mishiranu.dashchan.content.async.ReadCaptchaTask.CaptchaState state, boolean answerSaved, boolean restoring) {}
	public void updateSendState() {}
	public boolean canActivatePrimary() { return true; }
	public void setPrimaryChanged(Runnable callback) {}
	public boolean needsCaptchaSolution() { return false; }
	public void requestCaptchaSolution() {}
	public boolean confirmCaptchaDialog() { return false; }
	public void finishPrimaryTransition() {}
	public static int editorAvailableHeight(EditText editor, int available) { return available; }
	public static int minimumEditorInputHeight(EditText editor) { return 0; }
	public void formattingVisibility(boolean visible) {}
	public void styleMarkup(View button, int tag) {}
	public void resetCommands() {}
	public void styleCommand(View button) {}
	public void finishSetup(Button send, CompoundButton... options) {}
	public void activateToolbar(ViewGroup view) {}
	public void deactivateToolbar() {}
	public void close() {}
}
