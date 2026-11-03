package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached presentation fixtures only. No Activity, preference writes, cache, network or image export. ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class MaterialSmallTailsTest {
	private Context context(float fontScale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration c = new Configuration(base.getResources().getConfiguration()); c.fontScale = fontScale;
		return PostingTestContext.create(base.createConfigurationContext(c));
	}
	private int dp(View view, int value) { return StandardAppearance.dp(view, value); }
	private void layout(View view, int width, int height) {
		view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height > 0 ? dp(view, height) : 0,
						height > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}

	@Test public void mediaSliderSharesGeometryAndRestoresNativeDrawablesWithoutUserCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context c = context(1);
			for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				SeekBar seek = new SeekBar(c); seek.setLayoutDirection(direction);
				seek.setMax(95); seek.setProgress(27); seek.setSecondaryProgress(61);
				seek.setThumbOffset(3); seek.setSplitTrack(true); seek.setMinHeight(7); seek.setMaxHeight(9);
				ColorStateList tint = ColorStateList.valueOf(Color.MAGENTA);
				seek.setProgressTintList(tint); seek.setThumbTintList(tint); seek.setProgressBackgroundTintList(tint);
				layout(seek, 240, 48);
				Drawable progress = seek.getProgressDrawable(), thumb = seek.getThumb();
				Rect progressBounds = new Rect(progress.getBounds()), thumbBounds = new Rect(thumb.getBounds());
				int minimum = seek.getMinimumHeight();
				int[] userChanges = {0};
				seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
					@Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) userChanges[0]++; }
					@Override public void onStartTrackingTouch(SeekBar bar) {}
					@Override public void onStopTrackingTouch(SeekBar bar) {}
				});
				InterfaceAppearance.mediaSlider(seek);
				SeekBar normal = new SeekBar(c); InterfaceAppearance.slider(normal);
				InterfaceAppearance.renderResolved(normal, (InterfaceAppearance.State) normal.getTag(R.id.interface_appearance_state), true);
				for (int i = 0; i < 3; i++) {
					MediaContentAppearance.resolve(seek, true);
					assertEquals(normal.getThumb().getIntrinsicWidth(), seek.getThumb().getIntrinsicWidth());
					assertEquals(dp(seek, 20), seek.getThumb().getIntrinsicWidth());
					assertEquals(dp(seek, 6), seek.getMinHeight());
					assertTrue(seek.getMinimumHeight() >= dp(seek, 48));
					assertEquals(27, seek.getProgress()); assertEquals(61, seek.getSecondaryProgress()); assertEquals(95, seek.getMax());
					MediaContentAppearance.resolve(seek, false);
					assertSame(progress, seek.getProgressDrawable()); assertSame(thumb, seek.getThumb());
					assertSame(tint, seek.getProgressTintList()); assertSame(tint, seek.getThumbTintList());
					assertSame(tint, seek.getProgressBackgroundTintList());
					assertEquals(progressBounds, progress.getBounds()); assertEquals(thumbBounds, thumb.getBounds());
					assertEquals(3, seek.getThumbOffset()); assertTrue(seek.getSplitTrack());
					assertEquals(7, seek.getMinHeight()); assertEquals(9, seek.getMaxHeight());
					assertEquals(minimum, seek.getMinimumHeight()); assertEquals(0, userChanges[0]);
				}
			}
		});
	}

	@Test public void mediaLoadingHasOneCachedDrawableAndTheCommonReducedMotionContract() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ProgressBar bar = new ProgressBar(context(1));
			Drawable legacy = bar.getIndeterminateDrawable(); ColorStateList tint = bar.getIndeterminateTintList();
			int width = bar.getMinimumWidth(), height = bar.getMinimumHeight();
			InterfaceAppearance.mediaLoading(bar); StandardAppearance.resolveLoading(bar, true);
			Object owner = bar.getTag(R.id.standard_loading_appearance);
			StandardLoadingDrawable drawable = (StandardLoadingDrawable) bar.getIndeterminateDrawable();
			for (int i = 0; i < 3; i++) {
				InterfaceAppearance.mediaLoading(bar); InterfaceAppearance.refreshTree(bar);
				StandardAppearance.resolveLoading(bar, true);
				assertSame(owner, bar.getTag(R.id.standard_loading_appearance));
				assertSame(drawable, bar.getIndeterminateDrawable());
				assertNull(bar.getTag(R.id.standard_media_appearance));
				drawable.start(); drawable.sync(false); assertFalse(drawable.isRunning());
				bar.setVisibility(View.GONE); drawable.sync(true); assertFalse(drawable.isRunning());
				bar.setVisibility(View.VISIBLE);
				StandardAppearance.resolveLoading(bar, false);
				assertSame(legacy, bar.getIndeterminateDrawable()); assertSame(tint, bar.getIndeterminateTintList());
				assertEquals(width, bar.getMinimumWidth()); assertEquals(height, bar.getMinimumHeight());
				StandardAppearance.resolveLoading(bar, true); assertSame(drawable, bar.getIndeterminateDrawable());
			}
		});
	}

	@Test public void brushChipRetainsTheDataColorAndUsesTheMediaSelectionOutline() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View chip = new View(context(1)); int color = 0xffe53935;
			InterfaceAppearance.mediaColorChip(chip, color, true); layout(chip, 48, 48);
			for (boolean modern : new boolean[] {true, false, true}) {
				MediaContentAppearance.resolve(chip, modern);
				GradientDrawable drawable = (GradientDrawable) chip.getBackground();
				assertEquals(color, drawable.getColor().getDefaultColor());
				Bitmap bitmap = Bitmap.createBitmap(chip.getWidth(), chip.getHeight(), Bitmap.Config.ARGB_8888);
				try {
					drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight()); drawable.draw(new Canvas(bitmap));
					assertEquals(color, bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2));
					assertEquals(modern ? MediaContentAppearance.colors(chip).primary : 0xff64b5f6,
							bitmap.getPixel(bitmap.getWidth() / 2, dp(chip, 1)));
				} finally { bitmap.recycle(); }
			}
		});
	}

	@Test public void discussionStatusRestoresNormalColorAndNeverAccumulatesPadding() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TextView status = new TextView(context(2)); status.setTextSize(17); status.setTextColor(Color.MAGENTA);
			status.setPadding(12, 12, 12, 0); float size = status.getTextSize();
			status.setText("Длинное служебное сообщение\nLong informational message that must wrap within the discussion window.");
			int normal = 0;
			for (boolean error : new boolean[] {false, true, false, true, false}) {
				InterfaceAppearance.discussionStatus(status, error); StandardAppearance.resolveDiscussionStatus(status, true);
				if (normal == 0) normal = status.getCurrentTextColor();
				if (error) assertNotEquals(normal, status.getCurrentTextColor()); else assertEquals(normal, status.getCurrentTextColor());
				assertTrue(ColorUtils.calculateContrast(status.getCurrentTextColor(), ThemeEngine.getTheme(status.getContext()).card) >= 4.5);
				layout(status, 240, 0);
				assertEquals(status.length(), status.getLayout().getLineEnd(status.getLineCount() - 1));
				assertTrue(status.getHeight() >= status.getLayout().getHeight() + status.getCompoundPaddingTop());
				assertEquals(12, status.getPaddingTop()); assertEquals(0, status.getPaddingBottom());
			}
			status.setVisibility(View.GONE); StandardAppearance.resolveDiscussionStatus(status, true);
			assertEquals(View.GONE, status.getVisibility());
			StandardAppearance.resolveDiscussionStatus(status, false);
			assertEquals(size, status.getTextSize(), .01f); assertEquals(Color.MAGENTA, status.getCurrentTextColor());
		});
	}

	@Test public void drawerGlyphCentersAndRowAccessibilityTracksBinaryRecyclingWithOneClick() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context c = context(2);
			assertEquals(CheckBox.class, ScopeCheckBox.createDrawerSelection(c, false).getClass());
			for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				DrawerStyle style = new DrawerStyle(c); DrawerItemLayout row = new DrawerItemLayout(c, style);
				row.setLayoutDirection(direction); row.setMinimumHeight(style.rowHeight);
				ScopeCheckBox indicator = (ScopeCheckBox) ScopeCheckBox.createDrawerSelection(c, true);
				row.addView(indicator, dp(row, 48), style.rowHeight);
				TextView text = new TextView(c); style.apply(text, DrawerStyle.Role.THREAD);
				text.setText("Длинное название избранного треда / Favorite thread");
				row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
				View watcher = new View(c); row.addView(watcher, dp(row, 64), style.rowHeight);
				row.setContent(text, watcher); row.setThread(true); ScopeCheckBox.attachDrawerSelection(row, indicator);
				int[] clicks = {0}; boolean[] selected = {false};
				row.setOnClickListener(v -> { clicks[0]++; selected[0] = !selected[0];
					ScopeCheckBox.bindDrawerSelection(row, indicator, true, selected[0]); });
				ScopeCheckBox.bindDrawerSelection(row, indicator, true, false); row.performClick();
				assertEquals(1, clicks[0]); assertTrue(indicator.isChecked());
				layout(row, 280, 0); Rect glyph = new Rect(); indicator.getGlyphBounds(glyph);
				assertEquals(dp(row, 48), indicator.getWidth()); assertEquals(dp(row, 24), glyph.width());
				assertEquals(indicator.getWidth() / 2f, glyph.exactCenterX(), .5f);
				assertEquals(indicator.getHeight() / 2f, glyph.exactCenterY(), .5f);
				assertFalse(indicator.isClickable()); assertFalse(indicator.isFocusable()); assertTrue(indicator.isEnabled());
				assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, indicator.getImportantForAccessibility());
				AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
				assertTrue(info.isCheckable()); assertTrue(info.isChecked());
				indicator.setMixed(true); ScopeCheckBox.bindDrawerSelection(row, indicator, true, false);
				assertFalse(indicator.isMixed()); assertFalse(indicator.isChecked());
				ScopeCheckBox.bindDrawerSelection(row, indicator, false, true);
				assertFalse(indicator.isChecked()); assertEquals(View.GONE, indicator.getVisibility());
				info = row.createAccessibilityNodeInfo(); assertFalse(info.isCheckable()); assertFalse(info.isChecked());
				ScopeCheckBox independent = new ScopeCheckBox(c); independent.setMixed(true); independent.refreshAppearance();
				assertTrue(independent.isMixed());
			}
		});
	}
}
