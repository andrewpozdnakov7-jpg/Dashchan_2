package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared UI fixtures on the existing disposable debug Activity, not user data. NOT executed. */
@RunWith(AndroidJUnit4.class)
public class StandardDialogLifecycleTest {
	@Test public void customOnShowValidationAndCancelRemainSingleDispatch() {
		AtomicInteger shows = new AtomicInteger(), positives = new AtomicInteger(), cancels = new AtomicInteger(), dismisses = new AtomicInteger();
		AtomicReference<AlertDialog> current = new AtomicReference<>();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity); EditText input = new EditText(context);
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Fixture input")
						.setView(input).setPositiveButton(android.R.string.ok, (d, which) -> positives.incrementAndGet())
						.setNegativeButton(android.R.string.cancel, null).create();
				dialog.setOnShowListener(d -> {
					shows.incrementAndGet();
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
						if (input.length() == 0) { input.setError("Fixture validation"); return; }
						positives.incrementAndGet(); dialog.dismiss();
					});
				});
				dialog.setOnCancelListener(d -> cancels.incrementAndGet()); dialog.setOnDismissListener(d -> dismisses.incrementAndGet());
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get(); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(dialog.isShowing());
				assertEquals(0, positives.get()); dialog.cancel();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, shows.get()); assertEquals(1, cancels.get()); assertEquals(1, dismisses.get()); assertEquals(0, positives.get());
		}
	}
	@Test public void multiChoiceCheckedStateSurvivesStylingWithoutExecutingItsClickCallback() {
		AtomicInteger clicks = new AtomicInteger(); AtomicReference<AlertDialog> current = new AtomicReference<>();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Fixture choices")
						.setMultiChoiceItems(new CharSequence[]{"One", "Two", "Three"}, new boolean[]{true, false, true},
								(d, which, checked) -> clicks.incrementAndGet())
						.setPositiveButton(android.R.string.ok, null).create();
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get(); InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertTrue(dialog.getListView().isItemChecked(0)); assertFalse(dialog.getListView().isItemChecked(1));
				assertTrue(dialog.getListView().isItemChecked(2)); assertEquals(0, clicks.get()); dialog.dismiss();
			});
		}
	}
	@Test public void choiceDialogUsesOneSpacingOwnerAndAccentWithoutChangingSelection() {
		AtomicInteger clicks = new AtomicInteger(); AtomicReference<AlertDialog> current = new AtomicReference<>();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Long dialog title wrapping to another line")
						.setSingleChoiceItems(new String[]{"Disabled", "Enabled", "When replying without sage"}, 1,
								(d, which) -> clicks.incrementAndGet())
						.setNegativeButton(android.R.string.cancel, null).create();
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get();
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				android.widget.ListView list = dialog.getListView();
				assertEquals(0, list.getPaddingTop()); assertEquals(0, list.getPaddingBottom());
				assertTrue(list.isItemChecked(1)); assertEquals(0, clicks.get());
				android.view.View row = list.getAdapter().getView(1, null, list);
				assertTrue(row instanceof android.widget.CheckedTextView);
				android.widget.CheckedTextView text = (android.widget.CheckedTextView) row;
				AppearanceColors colors = StandardAppearance.colors(dialog.getContext());
				int checked = text.getCompoundDrawableTintList().getColorForState(
						new int[]{android.R.attr.state_enabled, android.R.attr.state_checked}, 0);
				assertEquals(InterfaceAppearance.contrasting(colors.primary, colors.surfaceContainer, 3), checked);
				android.widget.Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
				if (cancel.getParent() instanceof android.widget.LinearLayout) {
					android.widget.LinearLayout bar = (android.widget.LinearLayout) cancel.getParent();
					assertEquals(StandardAppearance.dp(cancel, 24), bar.getPaddingEnd() + cancel.getPaddingEnd());
				}
				dialog.dismiss();
			});
		}
	}
	@Test public void standardWindowIsPreparedBeforeShowAndLateNeutralButtonStillWorks() {
		AtomicReference<AlertDialog> current = new AtomicReference<>();
		AtomicReference<android.graphics.drawable.Drawable> surface = new AtomicReference<>();
		AtomicInteger width = new AtomicInteger(), shown = new AtomicInteger(), neutral = new AtomicInteger();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Cache size fixture")
						.setView(new android.widget.SeekBar(context)).setPositiveButton(android.R.string.ok, null).create();
				assertFalse(dialog.isShowing()); assertFalse(dialog.getWindow().getDecorView().isAttachedToWindow());
				assertNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
				width.set(dialog.getWindow().getAttributes().width); assertTrue(width.get() > 0);
				surface.set(dialog.getWindow().getDecorView().getBackground());
				dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Late neutral", (android.content.DialogInterface.OnClickListener) null);
				dialog.setOnShowListener(d -> {
					shown.incrementAndGet();
					dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> neutral.incrementAndGet());
				});
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get();
				assertEquals(width.get(), dialog.getWindow().getAttributes().width);
				assertSame(surface.get(), dialog.getWindow().getDecorView().getBackground());
				assertEquals("Late neutral", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
				assertTrue(dialog.isShowing()); assertEquals(1, neutral.get()); assertEquals(1, shown.get()); dialog.dismiss();
			});
		}
	}
	@Test public void menuWindowKeepsItsPreparedBoundsAndSurfaceThroughFirstTraversal() {
		AtomicReference<AlertDialog> current = new AtomicReference<>();
		AtomicReference<android.graphics.drawable.Drawable> surface = new AtomicReference<>();
		AtomicInteger width = new AtomicInteger();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				AlertDialog dialog = new com.mishiranu.dashchan.ui.DialogMenu(context).setTitle("Menu fixture")
						.add("First", () -> {}).add("Second", () -> {}).create();
				width.set(dialog.getWindow().getAttributes().width); assertTrue(width.get() > 0);
				surface.set(dialog.getWindow().getDecorView().getBackground()); current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get();
				assertEquals(width.get(), dialog.getWindow().getAttributes().width);
				assertSame(surface.get(), dialog.getWindow().getDecorView().getBackground()); dialog.dismiss();
			});
		}
	}
}
