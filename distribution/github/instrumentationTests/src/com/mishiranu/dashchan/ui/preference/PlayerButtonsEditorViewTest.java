package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.PlayerButtonsLayout;
import com.mishiranu.dashchan.media.PlayerButtonsLayout.Button;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.EnumMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Source handoff fixtures, not executed here. These do not write the installed user's preferences. */
@RunWith(AndroidJUnit4.class)
public class PlayerButtonsEditorViewTest {
	private Context context() {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		ThemeEngine.applyTheme(context);
		return context;
	}
	private PlayerButtonsEditorView editor(Context context) {
		return new PlayerButtonsEditorView(context, () -> new FrameLayout(context), () -> {});
	}
	private void measure(PlayerButtonsEditorView view, int widthDp) {
		// Fixtures change the model directly; production reload() already requests layout.
		view.board.requestLayout();
		view.requestLayout();
		view.measure(View.MeasureSpec.makeMeasureSpec(view.dp(widthDp), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void fourCellsFitBesideRightPanelOrStackWithoutShrinkingTargets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PlayerButtonsEditorView view = editor(context());
			for (int i = 0; i < 3; i++) {
				int width = new int[] {400, 360, 320}[i];
				view.board.model = PlayerButtonsLayout.defaults(); measure(view, width);
				assertEquals(4, view.board.effectiveColumns);
				assertEquals(4, view.board.model.columns);
				assertEquals(width == 320, view.board.stacked);
				if (view.board.stacked) assertTrue(view.board.rightZone.top > view.board.leftZone.bottom);
				else assertTrue(view.board.rightZone.left >= view.board.leftPanel.right + view.dp(8));
				assertEquals(view.board.buttons.get(Button.SPEED).getTop(),
						view.board.buttons.get(Button.SAVE).getTop());
				for (View button : view.board.buttons.values()) {
					assertTrue(button.getWidth() >= view.dp(48)); assertTrue(button.getHeight() >= view.dp(48));
				}
			}
		});
	}
	@Test public void widerRequestedRowsMoveRightPanelBelowAndKeepStableAnimationParticipants() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String saved = Preferences.getVideoButtonsLayout().encode();
			PlayerButtonsEditorView view = editor(context());
			EnumMap<Button, View> originals = new EnumMap<>(view.board.buttons);
			for (int columns : new int[] {4, 5, 6, 4}) {
				view.board.model = PlayerButtonsLayout.defaults().withColumns(columns); measure(view, 360);
				assertEquals(columns > 4, view.board.stacked);
				assertEquals(columns, view.board.effectiveColumns);
				assertTrue(view.furniture.contains(view.board.leftLabel));
				assertTrue(view.furniture.contains(view.board.rightLabel));
				if (view.board.stacked) {
					assertTrue(view.board.rightLabel.getTop() > view.board.leftZone.bottom);
					assertTrue(view.board.rightZone.top >= view.board.rightLabel.getBottom());
				}
				for (Button b : Button.values()) {
					View cell = view.board.buttons.get(b);
					assertSame(originals.get(b), cell);
					assertTrue(cell.getLeft() >= 0 && cell.getRight() <= view.board.getWidth());
					assertEquals(b, view.board.target(cell.getLeft() + 1, cell.getTop() + 1).button);
				}
			}
			assertEquals(saved, Preferences.getVideoButtonsLayout().encode());
		});
	}
	@Test public void extraButtonsWrapAboveBottomRowAndSixColumnsCanUseFullCardWidth() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PlayerButtonsEditorView view = editor(context());
			PlayerButtonsLayout allLeft = PlayerButtonsLayout.defaults().moveToEnd(Button.MUTE, false)
					.moveToEnd(Button.FULLSCREEN, false);
			for (int columns = 4; columns <= 6; columns++) {
				view.board.model = allLeft.withColumns(columns); measure(view, 360);
				assertEquals(columns, view.board.effectiveColumns);
				View first = view.board.buttons.get(allLeft.getLeft().get(0));
				for (int i = 0; i < allLeft.getLeft().size(); i++) {
					View cell = view.board.buttons.get(allLeft.getLeft().get(i));
					assertEquals(first.getLeft() + (i % columns) * view.board.cellWidth, cell.getLeft());
					assertEquals(first.getTop() - (i / columns) * view.board.cellHeight, cell.getTop());
					assertTrue(cell.getRight() <= view.board.getWidth());
				}
			}
		});
	}
	@Test public void panelsWrapRealButtonsAndRowsGrowUpwardsWithoutReplacingViews() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PlayerButtonsEditorView view = editor(context());
			EnumMap<Button, View> originals = new EnumMap<>(view.board.buttons);
			for (int columns = 1; columns <= 4; columns++) {
				view.board.model = PlayerButtonsLayout.defaults().withColumns(columns); measure(view, 600);
				assertEquals(columns * view.board.cellWidth + view.dp(8), (int) view.board.leftPanel.width());
				View first = view.board.buttons.get(Button.SPEED);
				assertEquals((int) view.board.leftPanel.bottom - view.dp(4), first.getBottom());
				assertEquals((int) view.board.leftPanel.left + view.dp(4), first.getLeft());
				for (Button b : Button.values()) assertSame(originals.get(b), view.board.buttons.get(b));
			}
		});
	}
	@Test public void emptyRightIsReachableAndAllSixFitAtWideSixColumns() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PlayerButtonsEditorView view = editor(context());
			view.board.model = PlayerButtonsLayout.defaults().moveToEnd(Button.MUTE, false)
					.moveToEnd(Button.FULLSCREEN, false).withColumns(6); measure(view, 600);
			assertEquals(6, view.board.effectiveColumns);
			assertEquals(view.dp(56), (int) view.board.rightPanel.width());
			assertTrue(view.board.rightZone.width() >= view.dp(96));
			RectF zone = view.board.rightZone;
			PlayerButtonsMotion.Target target = view.board.target(zone.centerX(), zone.top + view.dp(8));
			assertNotNull(target); assertNull(target.button); assertTrue(target.right);
		});
	}
	@Test public void buttonHitBeatsContainingZoneAndLabelsAreNotDropTargets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PlayerButtonsEditorView view = editor(context());
			view.board.model = PlayerButtonsLayout.defaults(); measure(view, 400);
			View mute = view.board.buttons.get(Button.MUTE);
			assertEquals(Button.MUTE, view.board.target(mute.getLeft() + 1, mute.getTop() + 1).button);
			assertNull(view.board.target(view.dp(4), view.dp(4)));
			assertNull(view.board.target(-1, view.board.leftZone.centerY()));
		});
	}
	@Test public void targetsUseExistingNoopSwapAndCapacityRulesWithoutSaving() {
		PlayerButtonsLayout layout = PlayerButtonsLayout.defaults();
		assertSame(layout, PlayerButtonsMotion.dropped(layout, Button.SAVE, null));
		assertSame(layout, PlayerButtonsMotion.dropped(layout, Button.SAVE, new PlayerButtonsMotion.Target(null, false)));
		assertSame(layout, PlayerButtonsMotion.dropped(layout, Button.SAVE, new PlayerButtonsMotion.Target(null, true)));
		assertSame(layout, PlayerButtonsMotion.dropped(layout, Button.SAVE, new PlayerButtonsMotion.Target(Button.SAVE, false)));
		PlayerButtonsLayout swap = PlayerButtonsMotion.dropped(layout, Button.SAVE, new PlayerButtonsMotion.Target(Button.MUTE, false));
		assertEquals(Button.SAVE, swap.getRight().get(0)); assertEquals(2, swap.getRight().size());
		PlayerButtonsLayout move = PlayerButtonsMotion.dropped(layout, Button.MUTE, new PlayerButtonsMotion.Target(null, false));
		assertEquals(Button.MUTE, move.getLeft().get(4)); assertEquals(1, move.getRight().size());
	}
	@Test public void modelOnlyReflowDoesNotWritePreferencesAndCleanupIsIdempotent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			String saved = Preferences.getVideoButtonsLayout().encode();
			PlayerButtonsEditorView view = editor(context());
			view.board.model = PlayerButtonsLayout.defaults().withColumns(1); measure(view, 320);
			view.finishVisuals(); view.finishVisuals();
			assertEquals(saved, Preferences.getVideoButtonsLayout().encode());
			assertFalse(view.isDragging());
			for (View button : view.board.buttons.values()) assertEquals(1f, button.getAlpha(), 0f);
		});
	}
	@Test public void enlargedFontRetainsMeasuredTargetsAndAccessibleBottomRows() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
			Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = 1.5f;
			PlayerButtonsEditorView view = editor(ThemeEngine.attach(base.createConfigurationContext(config)));
			view.board.model = PlayerButtonsLayout.defaults().withColumns(1); measure(view, 320);
			assertTrue(view.getMeasuredHeight() > 0);
			for (View button : view.board.buttons.values()) {
				assertTrue(button.getWidth() >= view.dp(48)); assertTrue(button.getHeight() >= view.dp(48));
				assertTrue(button.getTop() >= view.board.leftZone.top || view.board.model.getRight().contains(
						find(view, button)));
			}
		});
	}
	private Button find(PlayerButtonsEditorView view, View button) {
		for (Button b : Button.values()) if (view.board.buttons.get(b) == button) return b;
		throw new AssertionError();
	}
	@Test public void contrastFallbackDoesNotKeepInvisibleCustomAccent() {
		for (int background : new int[] {Color.WHITE, Color.BLACK, 0xff555555, 0xffe1e6f0}) {
			int visible = PlayerButtonsColors.contrast(background, background, 4.5);
			assertTrue(ColorUtils.calculateContrast(visible, background) >= 4.5);
		}
	}
}
