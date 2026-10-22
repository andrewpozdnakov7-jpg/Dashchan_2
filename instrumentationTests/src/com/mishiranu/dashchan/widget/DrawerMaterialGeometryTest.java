package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.service.WatcherService;
import com.mishiranu.dashchan.content.FontManager;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached view fixtures only: does not start polling or alter saved settings/favorites. */
@RunWith(AndroidJUnit4.class)
public class DrawerMaterialGeometryTest {
	private Context context(float scale) {
		Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(target.getResources().getConfiguration()); config.fontScale = scale;
		return ThemeEngine.attach(target.createConfigurationContext(config));
	}
	private DrawerItemLayout row(Context context, DrawerStyle style, TextView text, WatcherView watcher, boolean selection) {
		DrawerItemLayout row = new DrawerItemLayout(context, style);
		row.setPaddingRelative(style.dp(16), 0, style.dp(DrawerStyle.THREAD_END), 0);
		if (selection) row.addView(new CheckBox(context), style.dp(48), style.rowHeight);
		text.setText("A very long favorite title that must never overlap its tracking button");
		style.apply(text, DrawerStyle.Role.THREAD); text.setPaddingRelative(0, 0, style.dp(8), 0);
		text.setEllipsize(android.text.TextUtils.TruncateAt.END);
		row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		row.addView(watcher, style.chipWidth, style.rowHeight); row.setContent(text, watcher); row.setThread(true);
		return row;
	}
	private void layout(View view, int width) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void normalRowHasFixedTrailingChipAndNoTextOverlap() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); DrawerStyle style = new DrawerStyle(context);
			TextView text = new TextView(context);
			WatcherView watcher = new WatcherView(context, new WatcherView.ColorSet(0, 0, 0), style);
			DrawerItemLayout row = row(context, style, text, watcher, false); layout(row, style.dp(320));
			assertEquals(style.dp(16), text.getLeft());
			assertEquals(style.dp(320 - DrawerStyle.THREAD_END), watcher.getRight());
			assertEquals(style.chipWidth, watcher.getWidth());
			assertTrue(text.getRight() <= watcher.getLeft());
			assertTrue(style.rowHeight >= DrawerStyle.minimumRowHeight(context));
			assertEquals(style.rowHeight, row.getHeight());
			assertEquals(row.getHeight(), watcher.getHeight());
			assertTrue(style.chipHeight <= watcher.getHeight());
		});
	}
	@Test public void capsuleSizeDoesNotFollowHostWidthOrRowHeightInEitherProfile() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1f, 2f}) {
				Context c = context(scale); DrawerStyle style = new DrawerStyle(c);
				for (boolean modern : new boolean[]{false, true}) {
					WatcherView watcher = new WatcherView(c, new WatcherView.ColorSet(0, 0, 0), modern ? style : null);
					android.graphics.RectF previous = null;
					for (int extra : new int[]{0, style.dp(24), style.dp(60)}) {
						int width = (modern ? style.chipWidth : DrawerStyle.minimumRowHeight(c)) + extra;
						int height = style.rowHeight + extra;
						watcher.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
								View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
						watcher.layout(0, 0, width, height);
						android.graphics.RectF bounds = new android.graphics.RectF(); watcher.getCapsuleBounds(bounds);
						if (previous != null) { assertEquals(previous.width(), bounds.width(), 0); assertEquals(previous.height(), bounds.height(), 0); }
						assertEquals(width / 2f, bounds.centerX(), .5f); assertEquals(height / 2f, bounds.centerY(), .5f);
						if (modern) {
							View chip = watcher.getChildAt(0); assertEquals(style.chipWidth, chip.getWidth()); assertEquals(style.chipHeight, chip.getHeight());
							assertEquals(chip.getLeft(), bounds.left, 0); assertEquals(chip.getTop(), bounds.top, 0);
						}
						previous = bounds;
					}
				}
			}
		});
	}
	@Test public void compactTypographyUsesApprovedNormalBodyAndMediumHeadingWithoutLargerText() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); DrawerStyle style = new DrawerStyle(context);
			int expected = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
					14, context.getResources().getDisplayMetrics()));
			for (DrawerStyle.Role role : DrawerStyle.Role.values()) {
				TextView text = new TextView(context); style.apply(text, role);
				assertEquals(expected, text.getTextSize(), .01f);
				assertEquals(FontManager.resolveTypeface(context, role == DrawerStyle.Role.TITLE), text.getTypeface());
				assertEquals(0f, text.getLineSpacingExtra(), .01f);
				assertEquals(1f, text.getLineSpacingMultiplier(), .01f);
			}
			assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
					12, context.getResources().getDisplayMetrics())), style.counterTextSize, .01f);
		});
	}
	@Test public void narrowLargeFontRowMovesActionBelowInsteadOfShrinkingOrOverlappingText() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1.3f, 2f}) {
				Context context = context(scale); DrawerStyle style = new DrawerStyle(context);
				for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
					TextView text = new TextView(context);
					WatcherView watcher = new WatcherView(context, new WatcherView.ColorSet(0, 0, 0), style);
					DrawerItemLayout row = row(context, style, text, watcher, true);
					row.setLayoutDirection(direction); layout(row, style.dp(220));
					assertTrue(watcher.getTop() >= text.getBottom());
					assertTrue(watcher.getLeft() >= 0); assertTrue(watcher.getRight() <= row.getWidth());
					assertTrue(watcher.getBottom() <= row.getHeight()); assertEquals(2, text.getMaxLines());
				}
			}
		});
	}
	@Test public void capsuleDimensionsFollowMeasuredNumbersNotAFixedPercentage() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1f, 1.3f, 2f}) {
				Context context = context(scale); DrawerStyle style = new DrawerStyle(context);
				android.graphics.Paint paint = new android.graphics.Paint();
				paint.setTypeface(style.counterTypeface); paint.setTextSize(style.counterTextSize);
				paint.setFontFeatureSettings("tnum");
				int available = style.chipWidth - style.numberStart() - style.dp(DrawerStyle.CHIP_PADDING);
				for (int value : new int[]{0, 1, 999, 1000, 999999, 999999999, -999, -999999,
						-999999999, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
					assertTrue(paint.measureText(WatcherPresentation.format(value)) <= available);
				}
				assertTrue(style.chipWidth >= style.dp(64));
				assertTrue(style.chipHeight >= style.dp(28));
				assertTrue(style.chipHeight >= Math.ceil(paint.getFontMetrics().bottom - paint.getFontMetrics().top) + style.dp(6));
				assertEquals(style.chipHeight / 2f, style.chipRadius(), .01f);
				assertEquals(style.dp(26), style.numberStart());
				assertTrue(style.rowHeight >= style.chipHeight + style.dp(4));
			}
		});
	}
	@Test public void rebindingDoesNotExposePreviousThreadsTitleOrMaskedReplyCounts() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); DrawerStyle style = new DrawerStyle(context);
			WatcherView view = new WatcherView(context, new WatcherView.ColorSet(0, 0, 0), style);
			view.bindThread(1, "First");
			view.update(new WatcherService.Counter(WatcherService.Counter.State.ENABLED, true, 42, false, false));
			view.clearBinding(); view.bindThread(2, "Second");
			view.update(new WatcherService.Counter(WatcherService.Counter.State.DISABLED, true, 99, false, true));
			assertEquals("Second", view.getContentDescription().toString());
			assertEquals(context.getString(com.mishiranu.dashchan.R.string.drawer_tracking_off), view.getStateDescription());
			assertTrue(ColorUtils.calculateContrast(style.onSurface, style.surface) >= 4.5);
			assertTrue(ColorUtils.calculateContrast(style.onActive, style.active) >= 4.5);
			assertTrue(ColorUtils.calculateContrast(style.onNeutral, style.neutral) >= 4.5);
		});
	}
	@Test public void headingCounterHasMeasuredSixDpGapAndRetainsSemanticText() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); DrawerStyle style = new DrawerStyle(context);
			TextView text = new TextView(context); style.apply(text, DrawerStyle.Role.GROUP);
			android.graphics.Paint counter = new android.graphics.Paint();
			counter.setTypeface(style.counterTypeface); counter.setTextSize(style.counterTextSize);
			counter.setFontFeatureSettings("tnum");
			for (boolean rtl : new boolean[]{false, true}) {
				android.text.Spanned label = (android.text.Spanned) DrawerCountSpan.format("Board", 123, style, rtl);
				assertEquals("Board 123", label.toString());
				DrawerCountSpan[] spans = label.getSpans(0, label.length(), DrawerCountSpan.class);
				assertEquals(1, spans.length);
				int start = label.getSpanStart(spans[0]), end = label.getSpanEnd(spans[0]);
				assertEquals(5, start);
				assertEquals(style.dp(6) + (int) Math.ceil(counter.measureText("123")),
						spans[0].getSize(text.getPaint(), label, start, end, new android.graphics.Paint.FontMetricsInt()));
			}
		});
	}
}
