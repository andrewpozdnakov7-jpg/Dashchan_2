package com.mishiranu.dashchan.util;

import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.R;
import java.util.HashMap;

/** Opt-in gesture metadata only. Never read text, action titles, clipboard or key values. */
public final class InputGestureDiagnostics {
	private InputGestureDiagnostics() {}
	public static boolean isRecording() { return PostingFormDiagnostics.isRecording(); }
	public static String editorState(EditText editor) {
		if (editor == null) return "editor=none";
		int type = editor.getInputType();
		int variation = type & InputType.TYPE_MASK_VARIATION;
		boolean secret = editor.getId() == R.id.password || editor.getTransformationMethod() instanceof PasswordTransformationMethod
				|| (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
				&& (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
						|| variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
						|| variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
				|| (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
				&& variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
		return "editor=" + PostingFormDiagnostics.viewLabel(editor) + " focus=" + editor.hasFocus()
				+ " enabled=" + editor.isEnabled() + " shown=" + editor.isShown()
				+ " selection=" + (secret ? "redacted" : editor.getSelectionStart() + "," + editor.getSelectionEnd())
				+ " scroll=" + editor.getScrollX() + "," + editor.getScrollY()
				+ " canScrollY=" + editor.canScrollVertically(-1) + "," + editor.canScrollVertically(1)
				+ " size=" + editor.getWidth() + "x" + editor.getHeight();
	}
	public static void event(View view, String phase, String details) {
		if (!isRecording()) return;
		String line = "input_gesture phase=" + phase + " view=" + PostingFormDiagnostics.viewLabel(view) + " " + details;
		if (VideoDiagnostics.isExtendedRecording()) {
			// Use the existing 128 MiB UI ring, not the smaller geometry ring, without duplicating every MOVE.
			VideoDiagnostics.recordUi(line);
			Log.i("InputGestures", line);
		} else PostingFormDiagnostics.recordScreen(PostingFormDiagnostics.captureId(), line);
	}

	/** Per-view metadata; no View or user content is retained. MOVE samples are capped at 60 Hz. */
	public static final class Trace {
		private long capture, downTime, lastMoveAt;
		private int moves, moveSamples;
		private final HashMap<String, String> states = new HashMap<>();
		private void sync() {
			long current = PostingFormDiagnostics.captureId();
			if (capture == current) return;
			capture = current; downTime = lastMoveAt = 0; moves = moveSamples = 0; states.clear();
		}
		public void begin(MotionEvent motion) {
			if (!isRecording()) return;
			sync(); downTime = motion.getDownTime(); moves = moveSamples = 0; lastMoveAt = 0; states.clear();
		}
		public void state(View view, String phase, String details) {
			if (!isRecording()) return;
			sync();
			if (!details.equals(states.put(phase, details))) event(view, phase, "gesture=" + downTime + " " + details);
		}
		public void motion(View view, String phase, MotionEvent motion, String details) {
			if (!isRecording()) return;
			sync();
			int action = motion.getActionMasked();
			if (motion.getDownTime() != downTime || action == MotionEvent.ACTION_DOWN) {
				downTime = motion.getDownTime(); moves = moveSamples = 0; lastMoveAt = 0;
			}
			if (action == MotionEvent.ACTION_MOVE) {
				moves++;
				long now = SystemClock.uptimeMillis();
				if (lastMoveAt != 0 && now - lastMoveAt < 16) return;
				lastMoveAt = now; moveSamples++;
			}
			StringBuilder pointers = new StringBuilder();
			for (int i = 0; i < motion.getPointerCount(); i++) {
				if (i > 0) pointers.append(';');
				pointers.append(motion.getPointerId(i)).append(':').append(Math.round(motion.getX(i)))
						.append(',').append(Math.round(motion.getY(i))).append(':').append(motion.getToolType(i));
			}
			event(view, phase, "gesture=" + downTime + " eventMs=" + motion.getEventTime()
					+ " ageMs=" + Math.max(0, motion.getEventTime() - downTime)
					+ " action=" + MotionEvent.actionToString(motion.getAction())
					+ " pointers=" + pointers + " raw=" + Math.round(motion.getRawX()) + "," + Math.round(motion.getRawY())
					+ " source=" + motion.getSource() + " buttons=" + motion.getButtonState()
					+ " moves=" + moves + " moveSamples=" + moveSamples + " " + details);
		}
	}
}
