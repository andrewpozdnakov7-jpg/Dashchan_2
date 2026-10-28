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
	@Test public void nativeChoiceLayoutsKeepTitleListAndUpToThreeActionsInsideTheWindow() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			AtomicReference<AlertDialog> current = new AtomicReference<>();
			for (int choice = 0; choice < 3; choice++) for (int mask = 0; mask < 8; mask++) {
				final int mode = choice, options = mask;
				scenario.onActivity(activity -> {
					Context context = PostingTestContext.create(activity);
					org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
					AlertDialog.Builder builder = MotionDialogBuilder.standard(context);
					if ((options & 1) != 0) builder.setTitle("Delete font");
					CharSequence[] items = {"Open Sans", "Roboto", "Ubuntu"};
					if (mode == 0) builder.setItems(items, (d, which) -> {});
					else if (mode == 1) builder.setSingleChoiceItems(items, 1, (d, which) -> {});
					else builder.setMultiChoiceItems(items, new boolean[]{true, false, true}, (d, which, checked) -> {});
					int count = options >> 1;
					if (count > 0) builder.setPositiveButton("A long action label that must remain fully readable", null);
					if (count > 1) builder.setNegativeButton("A second long action label", null);
					if (count > 2) builder.setNeutralButton("A third long action label", null);
					AlertDialog dialog = builder.create(); current.set(dialog); dialog.show();
				});
				InstrumentationRegistry.getInstrumentation().waitForIdleSync();
				scenario.onActivity(activity -> {
					AlertDialog dialog = current.get(); android.widget.ListView list = dialog.getListView();
					InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
					assertTrue(list.isShown()); assertTrue(list.getHeight() > 0); assertEquals(3, list.getCount());
					assertEquals(0, list.getPaddingTop() - ((options & 1) != 0 ? 0 : StandardAppearance.dp(list, 16)));
					if (mode == 1) assertTrue(list.isItemChecked(1));
					if (mode == 2) { assertTrue(list.isItemChecked(0)); assertTrue(list.isItemChecked(2)); }
					android.graphics.Rect listRect = new android.graphics.Rect(); list.getGlobalVisibleRect(listRect);
					java.util.ArrayList<android.graphics.Rect> actions = new java.util.ArrayList<>();
					for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
						android.widget.Button button = dialog.getButton(which);
						if (button == null || button.getVisibility() != android.view.View.VISIBLE) continue;
						assertTrue(button.getHeight() >= StandardAppearance.dp(button, 48));
						android.graphics.Rect bounds = new android.graphics.Rect(); assertTrue(button.getGlobalVisibleRect(bounds));
						assertFalse(android.graphics.Rect.intersects(bounds, listRect));
						for (android.graphics.Rect other : actions) assertFalse(android.graphics.Rect.intersects(bounds, other));
						actions.add(bounds);
					}
					assertEquals(options >> 1, actions.size()); dialog.dismiss();
				});
			}
		}
	}
	@Test public void choiceRecyclingAndAppearanceRefreshKeepDisabledIdsAndSelectedCallback() {
		AtomicReference<AlertDialog> current = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
		AtomicReference<String> selected = new AtomicReference<>(); AtomicInteger shows = new AtomicInteger();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				String[] entries = {"Fixture one", "Disabled", "Fixture three"};
				android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, entries) {
					@Override public boolean areAllItemsEnabled() { return false; }
					@Override public boolean isEnabled(int position) { return position != 1; }
					@Override public boolean hasStableIds() { return true; }
					@Override public long getItemId(int position) { return 900 + position; }
					@Override public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
						android.widget.TextView text = (android.widget.TextView) super.getView(position, recycled, parent);
						text.setMinHeight(StandardAppearance.dp(text, 72)); text.setMinimumHeight(StandardAppearance.dp(text, 64));
						return text;
					}
				};
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Synthetic installed fonts")
						.setAdapter(adapter, (d, which) -> { selected.set(entries[which]); calls.incrementAndGet(); })
						.setNegativeButton(android.R.string.cancel, null).create();
				dialog.setOnShowListener(d -> { shows.incrementAndGet(); com.mishiranu.dashchan.content.FontManager.apply(dialog.getWindow().getDecorView()); });
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get(); android.widget.ListView list = dialog.getListView();
				for (int i = 0; i < 3; i++) InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertEquals(1, shows.get()); assertEquals(0, calls.get());
				assertFalse(list.getAdapter().isEnabled(1)); assertEquals(902, list.getAdapter().getItemId(2));
				assertTrue(list.getAdapter() instanceof android.widget.WrapperListAdapter);
				assertFalse(((android.widget.WrapperListAdapter) list.getAdapter()).getWrappedAdapter() instanceof android.widget.WrapperListAdapter);
				android.view.View row = list.getAdapter().getView(2, list.getChildAt(0), list);
				assertEquals(0, ((android.widget.TextView) row).getMinHeight());
				list.performItemClick(row, 2, 902);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, calls.get()); assertEquals("Fixture three", selected.get());
		}
	}
	@Test public void simpleInputRetainsTextSelectionAndOneOutsideInset() {
		AtomicReference<AlertDialog> current = new AtomicReference<>();
		AtomicReference<EditText> field = new AtomicReference<>();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				EditText input = new EditText(context); input.setSingleLine(false);
				input.setMinLines(1); input.setMaxLines(5);
				input.setText("A multiline favorite title\nsecond line"); input.setSelection(2, 9);
				android.widget.LinearLayout container = new android.widget.LinearLayout(context);
				container.addView(input, -1, -2);
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Rename")
						.setInputView(container).setPositiveButton(android.R.string.ok, null).create();
				current.set(dialog); field.set(input); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get(); EditText input = field.get();
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertEquals("A multiline favorite title\nsecond line", input.getText().toString());
				assertEquals(2, input.getSelectionStart()); assertEquals(9, input.getSelectionEnd());
				android.view.View container = (android.view.View) input.getParent();
				android.view.View outside = (android.view.View) container.getParent();
				assertEquals(0, container.getPaddingStart()); assertEquals(0, container.getPaddingEnd());
				assertEquals(StandardAppearance.dp(input, 24), outside.getPaddingStart());
				assertEquals(StandardAppearance.dp(input, 24), outside.getPaddingEnd());
				assertTrue(input.getMinimumHeight() >= StandardAppearance.dp(input, 56)); dialog.dismiss();
			});
		}
	}
	@Test public void messageAndActionsAreBothVisibleWithoutShiftingClickPositions() {
		AtomicReference<AlertDialog> current = new AtomicReference<>();
		AtomicInteger clicked = new AtomicInteger(-1), calls = new AtomicInteger();
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Context context = PostingTestContext.create(activity);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				AlertDialog dialog = MotionDialogBuilder.standard(context).setTitle("Update available")
						.setItemsWithMessage("Version details\nRelease notes",
								new String[]{"Open page", "Skip this version", "Cancel"}, (d, which) -> {
									clicked.set(which); calls.incrementAndGet();
								}).setPositiveButton("Open updates", null).create();
				current.set(dialog); dialog.show();
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(activity -> {
				AlertDialog dialog = current.get();
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				android.widget.TextView body = dialog.findViewById(android.R.id.message);
				assertNotNull(body); assertTrue(body.isShown()); assertTrue(body.getHeight() > 0);
				assertEquals("Version details\nRelease notes", body.getText().toString());
				android.widget.ListView list = dialog.getListView();
				assertTrue(list.isShown()); assertTrue(list.getHeight() > 0);
				assertEquals(3, list.getCount()); assertEquals(0, list.getHeaderViewsCount());
				assertEquals(0, calls.get());
				assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown());
				list.performItemClick(list.getChildAt(1 - list.getFirstVisiblePosition()), 1, list.getAdapter().getItemId(1));
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(1, calls.get()); assertEquals(1, clicked.get());
		}
	}
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
				int checked = text.getCheckMarkTintList().getColorForState(
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
				android.graphics.Point available = com.mishiranu.dashchan.util.ViewUtils.getWindowContentSize(context);
				int margin = StandardAppearance.dp(dialog.getWindow().getDecorView(),
						available.x < StandardAppearance.dp(dialog.getWindow().getDecorView(), 328) ? 16 : 24);
				assertEquals(Math.max(1, Math.min(StandardAppearance.dp(dialog.getWindow().getDecorView(),
						AppearanceShapes.STANDARD_DIALOG_MAX_WIDTH), available.x - 2 * margin)), width.get());
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
