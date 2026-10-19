package com.mishiranu.dashchan.content.security;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Presentation only. The Activity owns credentials, authentication and navigation. */
abstract class LockScreenForm {
	final EditText input;
	final TextView title, status;
	final Button submit, biometric, additional, usePin;
	View root;
	Runnable inputChanged;
	LockScreenForm(Context activity) {
		input = new EditText(activity);
		input.setId(View.generateViewId());
		AppLockActivity.configurePinInput(input);
		title = new TextView(activity); status = new TextView(activity);
		submit = createButton(activity); biometric = createButton(activity);
		additional = createButton(activity); usePin = createButton(activity);
		usePin.setVisibility(View.GONE);
		input.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override public void afterTextChanged(Editable value) {
				if (inputChanged != null) inputChanged.run();
			}
		});
	}
	protected Button createButton(Context activity) {
		Button button = new Button(activity);
		button.setFilterTouchesWhenObscured(true);
		return button;
	}
	/** Prompt IDs, not credentials, cross this boundary. */
	abstract void render(int heading, int description, int button, boolean pin, boolean bio,
			boolean extra, boolean welcome, boolean pinFallback);
	void available(boolean enabled) {
		input.setEnabled(enabled);
		submit.setEnabled(enabled && input.length() >= 6 && input.length() <= 12);
		biometric.setEnabled(enabled); additional.setEnabled(enabled); usePin.setEnabled(enabled);
	}
	boolean transitioning() { return false; }
	void error() {}
	void verifying() {}
	void biometricStarted() {}
	int success(boolean bio, boolean leaving, Runnable complete) { complete.run(); return 0; }
	boolean customExit() { return false; }
	void finish(Activity activity, Intent resume, boolean decorated) {
		if (resume != null) activity.startActivity(resume);
		activity.finish();
	}
	void dispose() {}
}
