package com.mishiranu.dashchan.widget;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ActionMode;
import android.view.View;
import android.widget.EditText;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.InputGestureDiagnostics;

/** Temporary native editor chrome suppression. Never changes selection, focus or IME state. */
@android.annotation.TargetApi(29)
public final class PredictiveTextSelection implements AutoCloseable, View.OnAttachStateChangeListener {

	// ActionMode.hide is bounded by Android. Renew only while this presentation owns the editor,
	// including a paused finger and the recovery animation, rather than on every animation frame.
	private static final long HIDE_MS = 1000, RENEW_MS = 400;
	private final EditText editor;
	private final Drawable left, right, middle;
	private final Drawable hiddenLeft, hiddenRight, hiddenMiddle;
	private ActionMode mode;
	private boolean closed;
	private final Runnable renew = this::hideToolbar;

	public PredictiveTextSelection(EditText editor, ActionMode mode) {
		this.editor = editor;
		left = editor.getTextSelectHandleLeft(); right = editor.getTextSelectHandleRight();
		middle = editor.getTextSelectHandle();
		hiddenLeft = transparent(left); hiddenRight = transparent(right); hiddenMiddle = transparent(middle);
		// Use new drawables, never alpha/tint mutations of shared theme resources. Public setters
		// also refresh already-visible native HandleViews (invalidating their separate windows).
		if (hiddenLeft != null) editor.setTextSelectHandleLeft(hiddenLeft);
		if (hiddenRight != null) editor.setTextSelectHandleRight(hiddenRight);
		if (hiddenMiddle != null) editor.setTextSelectHandle(hiddenMiddle);
		editor.addOnAttachStateChangeListener(this);
		onActionModeStarted(mode, editor);
		record("hide");
	}

	public void onActionModeStarted(ActionMode mode, View owner) {
		if (closed || owner != editor || mode == null || mode.getType() != ActionMode.TYPE_FLOATING) return;
		this.mode = mode;
		hideToolbar();
	}

	public void onActionModeFinished(ActionMode mode) {
		if (this.mode == mode) {
			this.mode = null;
			editor.removeCallbacks(renew);
		}
	}

	private void hideToolbar() {
		editor.removeCallbacks(renew);
		if (closed || mode == null) return;
		mode.hide(HIDE_MS);
		editor.postDelayed(renew, RENEW_MS);
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		editor.removeCallbacks(renew);
		editor.removeOnAttachStateChangeListener(this);
		// Do not overwrite a replacement installed by the field owner while the gesture ran.
		if (hiddenLeft != null && editor.getTextSelectHandleLeft() == hiddenLeft) editor.setTextSelectHandleLeft(left);
		if (hiddenRight != null && editor.getTextSelectHandleRight() == hiddenRight) editor.setTextSelectHandleRight(right);
		if (hiddenMiddle != null && editor.getTextSelectHandle() == hiddenMiddle) editor.setTextSelectHandle(middle);
		ActionMode previous = mode; mode = null;
		if (previous != null) {
			// Cancel Android's pending hide timer as well. No finish(), fake focus event or
			// selection rewrite: cancellation must leave the same native editing session alive.
			previous.hide(0);
			if (editor.isAttachedToWindow()) previous.invalidateContentRect();
		}
		record("restore");
	}

	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { close(); }

	private void record(String stage) {
		if (InputGestureDiagnostics.isRecording()) InputGestureDiagnostics.event(editor,
				"predictive_text_chrome", "stage=" + stage + " " + InputGestureDiagnostics.editorState(editor));
	}

	private static Drawable transparent(Drawable original) {
		return original != null ? new EmptyHandle(original) : null;
	}

	/** Keep the native handle's geometry, even if Android changes alpha while dragging it. */
	private static final class EmptyHandle extends BaseDrawable {
		private final int width, height, minimumWidth, minimumHeight;
		private final Rect padding = new Rect();
		private final boolean hasPadding;
		EmptyHandle(Drawable original) {
			width = original.getIntrinsicWidth(); height = original.getIntrinsicHeight();
			minimumWidth = original.getMinimumWidth(); minimumHeight = original.getMinimumHeight();
			hasPadding = original.getPadding(padding);
		}
		@Override public int getIntrinsicWidth() { return width; }
		@Override public int getIntrinsicHeight() { return height; }
		@Override public int getMinimumWidth() { return minimumWidth; }
		@Override public int getMinimumHeight() { return minimumHeight; }
		@Override public boolean getPadding(Rect out) { out.set(padding); return hasPadding; }
	}
}
