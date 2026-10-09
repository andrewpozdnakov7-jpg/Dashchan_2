package com.mishiranu.dashchan.ui.gallery;

import android.app.Dialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/** User close may animate; forced dismissal always tears down immediately. */
public final class AttachmentGridDialog extends Dialog {
	private AttachmentGridMotionController motion;

	public AttachmentGridDialog(Context context, int theme) { super(context, theme); }

	public void setMotionContent(View content, View source) {
		if (motion != null) motion.dispose();
		motion = new AttachmentGridMotionController(content, getWindow(), source);
	}

	@Override protected void onStart() {
		super.onStart();
		if (motion != null) motion.open();
	}

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		return motion != null && motion.isRunning() || super.dispatchTouchEvent(event);
	}

	@Override public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus && motion != null) motion.finish();
	}

	@Override public void cancel() {
		if (motion == null || !motion.close(this::cancelImmediately)) cancelImmediately();
	}

	private void cancelImmediately() { super.cancel(); }

	@Override public void dismiss() {
		if (motion != null) motion.dispose();
		super.dismiss();
	}

	@Override protected void onStop() {
		if (motion != null) motion.dispose();
		super.onStop();
	}
}
