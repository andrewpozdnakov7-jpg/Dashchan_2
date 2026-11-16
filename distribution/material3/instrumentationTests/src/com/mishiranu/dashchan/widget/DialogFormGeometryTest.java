package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Parent-budget and scroll-ownership fixtures. SOURCE ONLY / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class DialogFormGeometryTest {
	private Boolean previousIme;
	@org.junit.Before public void enableNewIme() {
		com.mishiranu.dashchan.util.SharedPreferences p = com.mishiranu.dashchan.content.Preferences.PREFERENCES;
		previousIme = p.contains(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME)
				? p.getBoolean(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, false) : null;
		try (com.mishiranu.dashchan.util.SharedPreferences.Editor e = p.edit()) { e.put(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, true); }
	}
	@org.junit.After public void restoreIme() {
		try (com.mishiranu.dashchan.util.SharedPreferences.Editor e = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
			if (previousIme == null) e.remove(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME);
			else e.put(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, previousIme);
		}
	}

	private static void layout(View view, int width, int height, int mode) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, mode));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void disabledModeKeepsPublishedMeasurementAndWindowPolicy() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try (com.mishiranu.dashchan.util.SharedPreferences.Editor e = com.mishiranu.dashchan.content.Preferences.PREFERENCES.edit()) {
				e.put(com.mishiranu.dashchan.content.Preferences.KEY_DIALOG_FORM_IME, false);
			}
			android.content.Context context = PostingTestContext.create();
			DialogFormScrollView actual = new DialogFormScrollView(context);
			ScrollView published = new ScrollView(context) {
				private final android.graphics.Rect visible = new android.graphics.Rect();
				@Override protected void onMeasure(int width, int height) {
					getWindowVisibleDisplayFrame(visible);
					int available = visible.height() > 0 ? visible.height()
							: com.mishiranu.dashchan.util.ViewUtils.getWindowContentSize(getContext()).y;
					int cap = Math.min(available, StandardAppearance.dp(this, AppearanceShapes.DIALOG_FORM_MAX_HEIGHT));
					if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, MeasureSpec.getSize(height));
					super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(0, cap), MeasureSpec.AT_MOST));
				}
			};
			actual.addView(new View(context), new ScrollView.LayoutParams(-1, 3000));
			published.addView(new View(context), new ScrollView.LayoutParams(-1, 3000));
			for (int mode : new int[] {View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST, View.MeasureSpec.UNSPECIFIED}) {
				layout(actual, 500, 600, mode); layout(published, 500, 600, mode);
				assertEquals(published.getMeasuredHeight(), actual.getMeasuredHeight());
			}
			android.app.AlertDialog dialog = MotionDialogBuilder.standard(context).setMessage("Fixture").create();
			android.view.WindowManager.LayoutParams before = new android.view.WindowManager.LayoutParams();
			before.copyFrom(dialog.getWindow().getAttributes());
			DialogFormInsets.configure(dialog);
			assertEquals(before.softInputMode, dialog.getWindow().getAttributes().softInputMode);
			assertEquals(before.getFitInsetsTypes(), dialog.getWindow().getAttributes().getFitInsetsTypes());
			assertEquals(before.isFitInsetsIgnoringVisibility(), dialog.getWindow().getAttributes().isFitInsetsIgnoringVisibility());
		});
	}
	@Test public void modernViewportWrapsCapsAndRespectsAnExactOuterSlot() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			DialogFormScrollView form = new DialogFormScrollView(PostingTestContext.create());
			View child = new View(form.getContext()); form.addView(child, new ScrollView.LayoutParams(-1, 30));
			int cap = StandardAppearance.dp(form, AppearanceShapes.DIALOG_FORM_MAX_HEIGHT);
			layout(form, 500, cap * 2, View.MeasureSpec.AT_MOST); assertEquals(30, form.getHeight());
			child.getLayoutParams().height = cap * 3;
			layout(form, 500, cap * 2, View.MeasureSpec.AT_MOST); assertEquals(cap, form.getHeight());
			layout(form, 500, 0, View.MeasureSpec.UNSPECIFIED); assertEquals(cap, form.getHeight());
			View slot = form.dialogContent(); layout(slot, 500, cap * 2, View.MeasureSpec.EXACTLY);
			assertEquals(cap * 2, slot.getHeight()); assertEquals(cap, form.getHeight());
			assertTrue(form.canScrollVertically(1));
		});
	}
	@Test public void legacyShrinksTheListThenUsesWholeFormScrollingWithoutReparentingFields() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			android.content.Context context = PostingTestContext.create();
			LinearLayout fields = new LinearLayout(context); fields.setOrientation(LinearLayout.VERTICAL);
			EditText input = new EditText(context); input.setText("retained selection"); input.setSelection(2, 7);
			fields.addView(input, new LinearLayout.LayoutParams(-1, 100));
			LinearLayout choices = new LinearLayout(context); choices.setOrientation(LinearLayout.VERTICAL);
			for (int i = 0; i < 50; i++) choices.addView(new View(context), new LinearLayout.LayoutParams(-1, 40));
			DialogFormScrollView form = DialogFormScrollView.create(context, fields, choices, false);
			View slot = form.dialogContent(); ScrollView list = (ScrollView) choices.getParent();
			int cap = StandardAppearance.dp(form, AppearanceShapes.DIALOG_FORM_MAX_HEIGHT);
			layout(slot, 500, cap + 200, View.MeasureSpec.AT_MOST);
			assertEquals(cap, list.getHeight()); assertFalse(form.canScrollVertically(1)); assertTrue(list.canScrollVertically(1));
			layout(slot, 500, 100 + cap / 2, View.MeasureSpec.AT_MOST);
			assertEquals(cap / 2, list.getHeight()); assertFalse(form.canScrollVertically(1));
			layout(slot, 500, 50, View.MeasureSpec.AT_MOST);
			assertTrue(form.canScrollVertically(1)); assertFalse(list.canScrollVertically(1));
			assertSame(fields, input.getParent()); assertEquals(2, input.getSelectionStart()); assertEquals(7, input.getSelectionEnd());
			layout(slot, 500, cap + 200, View.MeasureSpec.AT_MOST); assertEquals(cap, list.getHeight());
			assertEquals("retained selection", input.getText().toString());
		});
	}
}
