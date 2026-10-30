package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.Editable;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/** Prepared behavioral fixtures, not executed. Uses only synthetic text and a disposable debug Activity. */
public class StandardCustomContentTest {
	static void profile(boolean enabled, Runnable work) {
		Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		try { Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, enabled).close(); work.run(); }
		finally {
			if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
			else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
		}
	}
	static Object member(Object target, String name) {
		try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	static <T extends View> T find(View root, Class<T> type) {
		if (type.isInstance(root)) return type.cast(root);
		if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
			T found = find(((ViewGroup) root).getChildAt(i), type); if (found != null) return found;
		}
		return null;
	}
	@Test public void summaryKeepsRealCommentSelectionSpansAndCopiedBlocksAtBothScalesAndProfiles() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean enabled : new boolean[]{false, true}) profile(enabled, () -> {
				for (float scale : new float[]{1f, 2f}) {
					Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
					Configuration configuration = new Configuration(base.getResources().getConfiguration()); configuration.fontScale = scale;
					Context context = PostingTestContext.create(base.createConfigurationContext(configuration));
					AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Synthetic summary").create();
					SummaryLayout summary = SummaryLayout.standard(dialog);
					SpannableString body = new SpannableString("First value with a link");
					body.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					body.setSpan(new URLSpan("https://example.invalid/fixture"), 19, 23, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					summary.add("First heading", body); summary.addDivider(); summary.add("Second heading", "Second value");
					CommentTextView text = (CommentTextView) member(summary, "textView");
					String before = text.getText().toString();
					InterfaceAppearance.refreshTree(text); InterfaceAppearance.refreshTree(text);
					text.measure(View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(text, 240), View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
					assertTrue(text.getMeasuredHeight() > 0); assertEquals(before, text.getText().toString());
					Spanned spans = (Spanned) text.getText();
					assertEquals(1, spans.getSpans(0, spans.length(), StyleSpan.class).length);
					assertEquals(1, spans.getSpans(0, spans.length(), URLSpan.class).length);
					assertEquals("First heading\nFirst value with a link\n\nSecond heading\nSecond value",
							summary.onPrepareToCopy(text, new SpannableString(spans), 0, spans.length()));
					// Construction and refresh use the real class, whose forbidden setters throw immediately.
				}
			});
		});
	}
	@Test public void customFieldsDoNotMutateInputOrSelectionAndRadioIsStillRadioAfterRefreshAndOff() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> profile(true, () -> {
			Context context = PostingTestContext.create(); LinearLayout root = new LinearLayout(context);
			EditText input = new EditText(context); input.setSingleLine(true); input.setText("037"); input.setSelection(1, 2);
			input.setError("Synthetic validation"); int type = input.getInputType(); float size = input.getTextSize();
			int[] edits = {0}, checks = {0};
			input.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) { edits[0]++; }
				@Override public void afterTextChanged(Editable value) {}
			});
			RadioButton radio = new RadioButton(context); radio.setChecked(true);
			radio.setOnCheckedChangeListener((v, checked) -> checks[0]++);
			root.addView(input); root.addView(radio);
			InterfaceAppearance.standardCustomContent(root); InterfaceAppearance.refreshTree(root);
			assertEquals("037", input.getText().toString()); assertEquals(1, input.getSelectionStart()); assertEquals(2, input.getSelectionEnd());
			assertEquals(type, input.getInputType()); assertNotNull(input.getError()); assertEquals(0, edits[0]); assertEquals(0, checks[0]);
			assertTrue(radio.isChecked()); assertEquals(InterfaceAppearance.Role.CUSTOM_CHOICE,
					((InterfaceAppearance.State) radio.getTag(R.id.interface_appearance_state)).role);
			assertEquals(Boolean.TRUE, member(radio.getButtonDrawable(), "radio"));
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, false).close(); InterfaceAppearance.refreshTree(root);
			assertEquals(size, input.getTextSize(), .01f); assertNotNull(input.getError()); assertEquals(0, edits[0]); assertEquals(0, checks[0]);
		}));
	}
	private AlertDialog percentageDialog(Context context, boolean scale, Bundle saved) {
		try {
			Class<?> type = Class.forName("com.mishiranu.dashchan.ui.preference." +
					(scale ? "PostTextSizesFragment$ScalePreference" : "ThreadArrowTransparencyPreference"));
			Object preference;
			if (scale) {
				Constructor<?> ctor = type.getDeclaredConstructor(Context.class, String.class, int.class); ctor.setAccessible(true);
				preference = ctor.newInstance(context, Preferences.KEY_TEXT_SCALE, R.string.post_body_text_size);
			} else preference = type.getConstructor(Context.class).newInstance(context);
			Method create = type.getDeclaredMethod("createDialog", Bundle.class); create.setAccessible(true);
			return (AlertDialog) create.invoke(preference, saved);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	@Test public void percentageDialogsKeepExactTypedValueValidationAndRoundOnlyKeyboardSliderGestures() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				for (boolean enabled : new boolean[]{false, true}) profile(enabled, () -> {
					for (boolean scale : new boolean[]{false, true}) {
						Bundle saved = new Bundle(); saved.putString(scale ? "scale_input" : "input", scale ? "137" : "37");
						AlertDialog dialog = percentageDialog(PostingTestContext.create(activity), scale, saved);
						try {
							dialog.show(); EditText input = dialog.findViewById(android.R.id.edit);
							SeekBar seek = find(dialog.getWindow().getDecorView(), SeekBar.class); assertNotNull(seek);
							assertEquals(scale ? "137" : "37", input.getText().toString());
							input.setText(scale ? "143" : "43"); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
							assertEquals(scale ? "143" : "43", input.getText().toString());
							seek.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
							assertEquals(0, Integer.parseInt(input.getText().toString()) % (scale ? Preferences.STEP_TEXT_SCALE : 10));
							input.setText(""); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
							assertNotNull(input.getError()); assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
						} finally { dialog.dismiss(); }
						Bundle invalid = new Bundle(); invalid.putString(scale ? "scale_input" : "input", "");
						AlertDialog restored = percentageDialog(PostingTestContext.create(activity), scale, invalid);
						try { restored.show(); assertEquals("", ((EditText) restored.findViewById(android.R.id.edit)).getText().toString());
							assertFalse(restored.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
						} finally { restored.dismiss(); }
					}
				});
			});
		}
	}
}
