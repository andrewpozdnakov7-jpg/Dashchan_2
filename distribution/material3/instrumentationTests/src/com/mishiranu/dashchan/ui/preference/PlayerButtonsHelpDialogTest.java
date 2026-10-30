package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.media.PlayerButtonsLayout;
import com.mishiranu.dashchan.widget.PostingGestureTestActivity;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/** Prepared M04 fixture: no real editor commands or preference writes. NOT executed. */
public class PlayerButtonsHelpDialogTest {
	private static Object field(Object target, String name) throws ReflectiveOperationException {
		Field field = PlayerButtonsFragment.class.getDeclaredField(name); field.setAccessible(true); return field.get(target);
	}
	@Test public void missingEditorDoesNotOpenAndRepeatedHelpAndStopLeaveNoDuplicateWindow() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = ThemeEngine.attach(activity); ThemeEngine.applyTheme(context);
				PlayerButtonsFragment fragment = new PlayerButtonsFragment() { @Override public Context getContext() { return context; } };
				try {
					Method show = PlayerButtonsFragment.class.getDeclaredMethod("showHelp"); show.setAccessible(true);
					show.invoke(fragment); assertNull(field(fragment, "helpDialog"));
					PlayerButtonsEditorView editor = new PlayerButtonsEditorView(context, () -> new FrameLayout(context), () -> {},
							PlayerButtonsLayout::defaults, value -> { throw new AssertionError("Help must not commit editor changes"); });
					Field editorField = PlayerButtonsFragment.class.getDeclaredField("editor"); editorField.setAccessible(true); editorField.set(fragment, editor);
					show.invoke(fragment); AlertDialog first = (AlertDialog) field(fragment, "helpDialog"); assertTrue(first.isShowing());
					show.invoke(fragment); AlertDialog second = (AlertDialog) field(fragment, "helpDialog");
					assertFalse(first.isShowing()); assertTrue(second.isShowing());
					fragment.onStop(); assertFalse(second.isShowing()); assertNull(field(fragment, "helpDialog"));
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			});
		}
	}
}
