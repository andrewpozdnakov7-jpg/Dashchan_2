package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.EditText;
import com.mishiranu.dashchan.util.InputGestureDiagnostics;

// Removes spans on paste event.
// Native widget styling is supplied by ThemeEngine rather than AppCompat.
@android.annotation.SuppressLint("AppCompatCustomView")
public class SafePasteEditText extends EditText {
	private final InputGestureDiagnostics.Trace gestureTrace = new InputGestureDiagnostics.Trace();

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		if (event.getActionMasked() == MotionEvent.ACTION_DOWN) gestureTrace.begin(event);
		// Reserve gestures that start in this editor before the drawer can intercept them.
		// With it disabled the helper leaves the released native dispatch unchanged.
		CustomDrawerLayout.protectEditorGesture(this, event);
		boolean handled = super.dispatchTouchEvent(event);
		if (InputGestureDiagnostics.isRecording()) gestureTrace.motion(this, "editor_dispatch", event,
				"handled=" + handled + " " + InputGestureDiagnostics.editorState(this));
		return handled;
	}

	@Override public boolean performLongClick() {
		if (InputGestureDiagnostics.isRecording()) gestureTrace.state(this, "editor_long_press_begin", InputGestureDiagnostics.editorState(this));
		boolean handled = super.performLongClick();
		if (InputGestureDiagnostics.isRecording()) gestureTrace.state(this, "editor_long_press_end",
				"handled=" + handled + " " + InputGestureDiagnostics.editorState(this));
		return handled;
	}

	@Override protected void onSelectionChanged(int start, int end) {
		super.onSelectionChanged(start, end);
		// TextView's constructor can call this before field initialization.
		if (gestureTrace != null && InputGestureDiagnostics.isRecording()) {
			gestureTrace.state(this, "editor_selection", InputGestureDiagnostics.editorState(this));
		}
	}
	public SafePasteEditText(Context context) {
		super(context);
	}

	public SafePasteEditText(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public SafePasteEditText(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	/* init */ {
		ThemeEngine.applyStyle(this);
	}

	@SuppressWarnings("unused")
	public SafePasteEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	private static final InputFilter SPAN_FILTER = (source, start, end, dest, dstart, dend) ->
			source instanceof Spanned ? source.toString() : source;

	@Override
	public boolean onTextContextMenuItem(int id) {
		if (id == android.R.id.paste) {
			Editable editable = getEditableText();
			InputFilter[] filters = editable.getFilters();
			InputFilter[] tempFilters = new InputFilter[filters != null ? filters.length + 1 : 1];
			if (filters != null) {
				System.arraycopy(filters, 0, tempFilters, 1, filters.length);
			}
			tempFilters[0] = SPAN_FILTER;
			editable.setFilters(tempFilters);
			try {
				return super.onTextContextMenuItem(id);
			} finally {
				editable.setFilters(filters);
			}
		} else {
			return super.onTextContextMenuItem(id);
		}
	}
}
