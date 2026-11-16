package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Source-only device fixtures: ADDED / NOT RUN. Real popup visibility still needs a phone. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class PredictiveTextSelectionTest {

	private static final class Mode extends ActionMode {
		final ArrayList<Long> hides = new ArrayList<>();
		boolean finished;
		Mode() { setType(TYPE_FLOATING); }
		@Override public void hide(long duration) { hides.add(duration); }
		@Override public void finish() { finished = true; }
		@Override public void invalidate() {}
		@Override public void setTitle(CharSequence value) {}
		@Override public void setTitle(int value) {}
		@Override public void setSubtitle(CharSequence value) {}
		@Override public void setSubtitle(int value) {}
		@Override public void setCustomView(View view) {}
		@Override public CharSequence getTitle() { return null; }
		@Override public CharSequence getSubtitle() { return null; }
		@Override public View getCustomView() { return null; }
		@Override public Menu getMenu() { return null; }
		@Override public MenuInflater getMenuInflater() { return null; }
	}

	private static Drawable handle() {
		GradientDrawable drawable = new GradientDrawable(); drawable.setColor(Color.RED); drawable.setSize(17, 29);
		return drawable;
	}
	private static int pixel(Drawable drawable) {
		Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
		android.graphics.Rect bounds = new android.graphics.Rect(drawable.getBounds());
		try { drawable.setBounds(0, 0, 32, 32); drawable.draw(new Canvas(bitmap)); return bitmap.getPixel(16, 16); }
		finally { drawable.setBounds(bounds); bitmap.recycle(); }
	}
	private static EditText editor() {
		EditText editor = new EditText(ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()));
		editor.setText("retained draft"); editor.setSelection(2, 7);
		editor.setTextSelectHandleLeft(handle()); editor.setTextSelectHandleRight(handle()); editor.setTextSelectHandle(handle());
		return editor;
	}

	@Test public void hidingAndCancellationPreserveEditingStateAndRestoreExactDrawables() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText editor = editor(); Mode mode = new Mode();
			Drawable left = editor.getTextSelectHandleLeft(), right = editor.getTextSelectHandleRight(), middle = editor.getTextSelectHandle();
			Drawable cursor = editor.getTextCursorDrawable(); Object text = editor.getText(); boolean focused = editor.hasFocus();
			int type = editor.getInputType(), ime = editor.getImeOptions();
			PredictiveTextSelection presentation = new PredictiveTextSelection(editor, mode);
			try {
				for (Drawable hidden : new Drawable[] {editor.getTextSelectHandleLeft(), editor.getTextSelectHandleRight(), editor.getTextSelectHandle()}) {
					assertEquals(17, hidden.getIntrinsicWidth()); assertEquals(29, hidden.getIntrinsicHeight());
					hidden.setAlpha(255); assertEquals(Color.TRANSPARENT, pixel(hidden));
				}
				assertEquals(Color.RED, pixel(left)); assertEquals(Color.RED, pixel(right));
				assertEquals(Long.valueOf(1000), mode.hides.get(0)); assertFalse(mode.finished);
			} finally { presentation.close(); }
			assertSame(left, editor.getTextSelectHandleLeft()); assertSame(right, editor.getTextSelectHandleRight());
			assertSame(middle, editor.getTextSelectHandle()); assertSame(cursor, editor.getTextCursorDrawable());
			assertSame(text, editor.getText()); assertEquals(2, editor.getSelectionStart()); assertEquals(7, editor.getSelectionEnd());
			assertEquals(focused, editor.hasFocus()); assertEquals(type, editor.getInputType()); assertEquals(ime, editor.getImeOptions());
			assertEquals(Long.valueOf(0), mode.hides.get(mode.hides.size() - 1)); assertFalse(mode.finished);
			int calls = mode.hides.size(); presentation.close(); assertEquals(calls, mode.hides.size());
		});
	}

	@Test public void teardownDoesNotReshowFinishedModeOrOverwriteLaterFieldAppearance() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText editor = editor(); Mode mode = new Mode();
			PredictiveTextSelection presentation = new PredictiveTextSelection(editor, mode);
			Drawable replacement = handle(); editor.setTextSelectHandleLeft(replacement);
			presentation.onActionModeFinished(mode);
			presentation.onViewDetachedFromWindow(editor); presentation.close();
			assertSame(replacement, editor.getTextSelectHandleLeft());
			assertEquals(1, mode.hides.size()); assertFalse(mode.finished);
			assertEquals(2, editor.getSelectionStart()); assertEquals(7, editor.getSelectionEnd());
		});
	}

	@Test public void renewedModeMustBelongToTheSameEditorAndOldFinishCannotReleaseIt() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText editor = editor(); Mode first = new Mode(), foreign = new Mode(), next = new Mode();
			PredictiveTextSelection presentation = new PredictiveTextSelection(editor, first);
			try {
				presentation.onActionModeStarted(foreign, editor()); assertTrue(foreign.hides.isEmpty());
				presentation.onActionModeFinished(first);
				presentation.onActionModeStarted(next, editor); presentation.onActionModeFinished(first);
				assertEquals(Long.valueOf(1000), next.hides.get(0));
			} finally { presentation.close(); }
			assertEquals(1, first.hides.size()); assertTrue(foreign.hides.isEmpty());
			assertEquals(Long.valueOf(0), next.hides.get(next.hides.size() - 1));
		});
	}
}
