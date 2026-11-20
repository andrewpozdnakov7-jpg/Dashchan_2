package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.CheckBox;
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
	private static View contentWithHeight(Context context, int height) {
		View child = new View(context);
		child.setMinimumHeight(height);
		return child;
	}

	private static void layout(View view, int width, int height, int mode) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, mode));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void formFitsImeInsetsWhilePreservingTheKeyboardVisibilityPolicy() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			AlertDialog dialog = MotionDialogBuilder.standard(context).setMessage("Fixture").create();
			Window window = dialog.getWindow();
			assertNotNull(window);
			window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
			WindowManager.LayoutParams before = new WindowManager.LayoutParams();
			before.copyFrom(window.getAttributes());
			DialogFormInsets.configure(dialog);
			WindowManager.LayoutParams after = window.getAttributes();
			assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
					after.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
			assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
					after.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
			assertEquals(before.getFitInsetsTypes() | WindowInsets.Type.ime(), after.getFitInsetsTypes());
			assertEquals(before.getFitInsetsSides(), after.getFitInsetsSides());
			assertFalse(after.isFitInsetsIgnoringVisibility());
		});
	}
	@Test public void modernViewportWrapsCapsAndRespectsAnExactOuterSlot() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			DialogFormScrollView form = new DialogFormScrollView(PostingTestContext.create());
			View child = contentWithHeight(form.getContext(), 30);
			form.addView(child, new ScrollView.LayoutParams(-1, -2));
			int cap = StandardAppearance.dp(form, AppearanceShapes.DIALOG_FORM_MAX_HEIGHT);
			layout(form, 500, cap * 2, View.MeasureSpec.AT_MOST); assertEquals(30, form.getHeight());
			child.setMinimumHeight(cap * 3);
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
			choices.addView(new View(context), new LinearLayout.LayoutParams(-1, 40));
			CheckBox firstBoard = new CheckBox(context);
			firstBoard.setText("Board");
			firstBoard.setMinHeight(64);
			choices.addView(firstBoard, new LinearLayout.LayoutParams(-1, -2));
			for (int i = 0; i < 50; i++) choices.addView(new View(context), new LinearLayout.LayoutParams(-1, 40));
			DialogFormScrollView form = DialogFormScrollView.create(context, fields, choices, false);
			View slot = form.dialogContent(); ScrollView list = (ScrollView) choices.getParent();
			int cap = StandardAppearance.dp(form, AppearanceShapes.DIALOG_FORM_MAX_HEIGHT);
			layout(slot, 500, cap + 200, View.MeasureSpec.AT_MOST);
			assertEquals(cap, list.getHeight()); assertFalse(form.canScrollVertically(1)); assertTrue(list.canScrollVertically(1));
			int rowHeight = firstBoard.getHeight();
			assertTrue(rowHeight >= 64 && rowHeight <= cap);
			list.scrollTo(0, 80);
			layout(slot, 500, 100 + rowHeight, View.MeasureSpec.AT_MOST);
			assertEquals(rowHeight, list.getHeight());
			assertFalse(form.canScrollVertically(1));
			assertEquals(80, list.getScrollY());
			layout(slot, 500, 100 + rowHeight - 1, View.MeasureSpec.AT_MOST);
			assertTrue(form.canScrollVertically(1));
			assertFalse(list.canScrollVertically(1));
			assertFalse(list.canScrollVertically(-1));
			assertEquals(180, form.getScrollY());
			layout(slot, 500, 101, View.MeasureSpec.AT_MOST);
			assertTrue(form.canScrollVertically(1));
			assertFalse(list.canScrollVertically(1));
			layout(slot, 500, 100 + rowHeight, View.MeasureSpec.AT_MOST);
			assertEquals(0, form.getScrollY());
			assertEquals(80, list.getScrollY());
			layout(slot, 500, 50, View.MeasureSpec.AT_MOST);
			assertTrue(form.canScrollVertically(1)); assertFalse(list.canScrollVertically(1));
			assertSame(fields, input.getParent()); assertEquals(2, input.getSelectionStart()); assertEquals(7, input.getSelectionEnd());
			layout(slot, 500, cap + 200, View.MeasureSpec.AT_MOST); assertEquals(cap, list.getHeight());
			assertEquals("retained selection", input.getText().toString());
		});
	}
}
