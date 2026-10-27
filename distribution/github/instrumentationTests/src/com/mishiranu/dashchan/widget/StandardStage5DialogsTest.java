package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.DialogMenu;
import com.mishiranu.dashchan.ui.ForegroundManager;
import com.mishiranu.dashchan.ui.preference.PlaybackSpeedDialog;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

/** Prepared only. Real dialog input and synthetic pending records; no forum, service or network operation. */
public class StandardStage5DialogsTest {
	static Object field(Object value, String name) {
		try {
			for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) {
				try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(value); }
				catch (NoSuchFieldException ignored) {}
			}
			throw new NoSuchFieldException(name);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	static <T extends View> T find(View root, Class<T> type) { return StandardCustomContentTest.find(root, type); }
	static Button button(View root, String text) {
		if (root instanceof Button && text.contentEquals(((Button) root).getText())) return (Button) root;
		if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
			Button result = button(((ViewGroup) root).getChildAt(i), text); if (result != null) return result;
		}
		return null;
	}
	static AlertDialog speed(FragmentManager fm, int[] result) {
		PlaybackSpeedDialog.show(fm, 1000, value -> { result[0]++; result[1] = value; });
		fm.executePendingTransactions();
		return (AlertDialog) ((DialogFragment) fm.findFragmentByTag(PlaybackSpeedDialog.class.getName())).requireDialog();
	}
	@Test public void exactSpeedSurvivesProgrammaticSliderAndRefreshInvalidInputAndCancelDoNotCommit() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				for (boolean enabled : new boolean[]{false, true}) StandardCustomContentTest.profile(enabled, () -> {
					int[] result = {0, 0}; AlertDialog d = speed(a.getSupportFragmentManager(), result);
					try {
						EditText input = find(d.getWindow().getDecorView(), EditText.class);
						SeekBar slider = find(d.getWindow().getDecorView(), SeekBar.class);
						input.setText("143"); slider.setProgress(0); InterfaceAppearance.refreshTree(d.getWindow().getDecorView());
						assertEquals("143", input.getText().toString()); assertEquals(0, result[0]);
						input.setText("0"); d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
						assertTrue(d.isShowing()); assertNotNull(input.getError()); assertEquals(0, result[0]);
						d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); assertEquals("100", input.getText().toString());
						input.setText("143"); d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
						assertEquals(1, result[0]); assertEquals(1430, result[1]);
					} finally { d.dismiss(); a.getSupportFragmentManager().executePendingTransactions(); }
					AlertDialog cancelled = speed(a.getSupportFragmentManager(), result);
					find(cancelled.getWindow().getDecorView(), EditText.class).setText("999"); cancelled.cancel();
					assertEquals(1, result[0]); a.getSupportFragmentManager().executePendingTransactions();
				});
			});
		}
	}
	@Test public void dynamicPresetRowsRespectLimitRemoveResetOrderAndValidation() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> StandardCustomContentTest.profile(true, () -> {
				int[][] result = {null};
				PlaybackSpeedDialog.showPresets(a.getSupportFragmentManager(), new int[]{1000, 1500}, value -> result[0] = value);
				a.getSupportFragmentManager().executePendingTransactions();
				AlertDialog d = (AlertDialog) ((DialogFragment) a.getSupportFragmentManager()
						.findFragmentByTag(PlaybackSpeedDialog.class.getName() + ":presets")).requireDialog();
				try {
					EditText first = find(d.getWindow().getDecorView(), EditText.class);
					LinearLayout rows = (LinearLayout) first.getParent().getParent();
					Button add = button(d.getWindow().getDecorView(), a.getString(R.string.add_playback_speed_preset));
					for (int i = 0; i < 15; i++) add.performClick();
					assertEquals(12, rows.getChildCount()); assertFalse(add.isEnabled());
					for (int i = 0; i < rows.getChildCount(); i++) find(rows.getChildAt(i), EditText.class).setText(Integer.toString(10 + i));
					find(rows.getChildAt(1), EditText.class).setText("10");
					d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertNull(result[0]); assertTrue(d.isShowing());
					while (rows.getChildCount() > 1) ((ViewGroup) rows.getChildAt(0)).getChildAt(2).performClick();
					((ViewGroup) rows.getChildAt(0)).getChildAt(2).performClick();
					assertEquals("", find(rows, EditText.class).getText().toString()); assertTrue(add.isEnabled());
					d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
					assertEquals(Preferences.getDefaultVideoPlaybackSpeedPresets().length, rows.getChildCount());
					add.performClick(); assertTrue(add.isEnabled());
					((ViewGroup) rows.getChildAt(rows.getChildCount() - 1)).getChildAt(2).performClick();
					d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
					assertArrayEquals(Preferences.getDefaultVideoPlaybackSpeedPresets(), result[0]);
				} finally { d.dismiss(); }
			}));
		}
	}
	@Test public void standardMenuUpdateRebindsCurrentFileAndCheckWithoutRunningCallbacksDuringRefresh() {
		int[] called = {0, 0};
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> StandardCustomContentTest.profile(true, () -> {
				Context c = PostingTestContext.create(a);
				DialogMenu old = new DialogMenu(c).setTitle("First synthetic file"); old.addCheck(R.string.save, true, () -> called[0]++);
				AlertDialog d = old.create();
				try {
					d.show(); InterfaceAppearance.refreshTree(d.getWindow().getDecorView());
					DialogMenu next = new DialogMenu(c).setTitle("Second synthetic file"); next.add(R.string.save, () -> called[1]++); next.update(d);
					androidx.recyclerview.widget.RecyclerView list = d.findViewById(R.id.standard_menu_list);
					assertEquals(1, list.getAdapter().getItemCount());
					assertEquals(0, called[0]); assertEquals(0, called[1]);
					list.measure(View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(list, 320), View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(list, 200), View.MeasureSpec.EXACTLY));
					list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
					androidx.recyclerview.widget.RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
					assertNotNull(holder); holder.itemView.performClick();
				} finally { d.dismiss(); }
			}));
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			assertEquals(0, called[0]); assertEquals(1, called[1]);
		}
	}
	@Test public void itemChoiceHeaderOffsetsSingleMultiResultsAndCancelRemainUnchanged() {
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(a -> {
				for (boolean enabled : new boolean[]{false, true}) StandardCustomContentTest.profile(enabled, () -> {
					for (boolean image : new boolean[]{false, true}) for (boolean multiple : new boolean[]{false, true}) {
						String id = "stage5-fixture-" + java.util.UUID.randomUUID(); Object pending;
						try {
							Constructor<?> ctor = Class.forName("com.mishiranu.dashchan.ui.ForegroundManager$ChoicePendingData").getDeclaredConstructor();
							ctor.setAccessible(true); pending = ctor.newInstance();
						} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
						@SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) field(ForegroundManager.getInstance(), "pendingDataMap");
						synchronized (map) { map.put(id, pending); }
						Bitmap bitmap = image ? Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) : null;
						ForegroundManager.ItemChoiceDialog f = new ForegroundManager.ItemChoiceDialog(id, new boolean[]{true, false},
								new CharSequence[]{"First synthetic choice", "Second synthetic choice"}, "Synthetic description", bitmap, multiple);
						try {
							f.showNow(a.getSupportFragmentManager(), id); AlertDialog d = (AlertDialog) f.requireDialog(); ListView list = d.getListView();
							int offset = image ? 1 : 0; assertEquals(2 + offset, list.getCount());
							assertTrue(list.isItemChecked(offset)); if (image) assertFalse(list.getAdapter().isEnabled(0));
							View firstRow = list.getAdapter().getView(offset, null, list);
							View secondRow = list.getAdapter().getView(offset + 1, firstRow, list);
							assertEquals("Second synthetic choice", ((TextView) secondRow.findViewById(android.R.id.text1)).getText().toString());
							f.onItemClick(list, secondRow, offset + 1, offset + 1);
							if (multiple) { assertTrue(list.isItemChecked(offset + 1)); f.onClick(d, AlertDialog.BUTTON_POSITIVE); }
							assertArrayEquals(multiple ? new boolean[]{true, true} : new boolean[]{false, true}, (boolean[]) field(pending, "result"));
							f.onCancel(d); assertNull(field(pending, "result"));
						} finally {
							f.dismissNow(); synchronized (map) { map.remove(id); } if (bitmap != null) bitmap.recycle();
						}
					}
				});
			});
		}
	}
}
