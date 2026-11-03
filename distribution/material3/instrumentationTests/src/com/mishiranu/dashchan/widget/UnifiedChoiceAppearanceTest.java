package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.OpaqueColors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression fixtures for shared native semantics and real rendered glyph pixels. ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class UnifiedChoiceAppearanceTest {
	private static void profile(boolean material, Runnable check) {
		Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		try { Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, material).close(); check.run(); }
		finally {
			if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
			else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
		}
	}
	private static ChoiceIndicatorDrawable glyph(Drawable drawable, boolean radio) {
		assertTrue(drawable instanceof ChoiceIndicatorDrawable);
		ChoiceIndicatorDrawable glyph = (ChoiceIndicatorDrawable) drawable; assertEquals(radio, glyph.isRadio()); return glyph;
	}
	@Test public void repeatedStyleKeepsCheckboxListenersAndRadioGroupExclusivityInBothInterfaces() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			for (boolean material : new boolean[] {false, true}) profile(material, () -> {
				FrameLayout root = new FrameLayout(context); CheckBox check = new CheckBox(context);
				check.setText("Option"); check.setChecked(true); check.setClickable(false); root.addView(check);
				int[] changes = {0}; check.setOnCheckedChangeListener((v, checked) -> changes[0]++);
				RadioGroup group = new RadioGroup(context); RadioButton first = new RadioButton(context), second = new RadioButton(context);
				first.setId(View.generateViewId()); second.setId(View.generateViewId()); group.addView(first); group.addView(second);
				first.setChecked(true); root.addView(group);
				for (int i = 0; i < 3; i++) {
					ThemeEngine.applyStyle(check); ThemeEngine.applyStyle(first); ThemeEngine.applyStyle(second);
					InterfaceAppearance.check(check); InterfaceAppearance.check(first); InterfaceAppearance.refreshTree(root);
				}
				ChoiceIndicatorDrawable checkGlyph = glyph(check.getButtonDrawable(), false);
				assertEquals(Math.round(24 * context.getResources().getDisplayMetrics().density), checkGlyph.getIntrinsicWidth());
				glyph(first.getButtonDrawable(), true); glyph(second.getButtonDrawable(), true);
				assertTrue(check.isChecked()); assertFalse(check.isClickable()); assertEquals("Option", check.getText().toString());
				assertEquals(0, changes[0]); second.performClick(); assertTrue(second.isChecked()); assertFalse(first.isChecked());
				check.setChecked(false); assertEquals(1, changes[0]);
			});
		});
	}
	@Test public void bothAndroidIndicatorPlacementsUseTheSameGlyphWithoutDuplicatingOrMovingOtherIcons() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create(); AppearanceColors colors = StandardAppearance.colors(context);
			for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				for (int slot : new int[] {-1, 0, 2}) for (boolean single : new boolean[] {false, true}) {
					CheckedTextView view = new CheckedTextView(context); view.setLayoutDirection(direction);
					view.setText("Native row"); view.setChecked(true); view.setCompoundDrawablePadding(13);
					Drawable marker = new ColorDrawable(Color.BLACK), other = new ColorDrawable(Color.RED);
					marker.setBounds(0, 0, 24, 24); other.setBounds(0, 0, 8, 8);
					view.setCheckMarkDrawable(slot < 0 ? marker : null);
					view.setCompoundDrawablesRelative(slot == 0 ? marker : null, other, slot == 2 ? marker : null, null);
					for (int i = 0; i < 3; i++) {
						ThemeEngine.applyStyle(view);
						ChoiceAppearance.apply(view, single, ChoiceAppearance.colors(colors, colors.surfaceContainer));
					}
					Drawable[] drawables = view.getCompoundDrawablesRelative();
					glyph(slot < 0 ? view.getCheckMarkDrawable() : drawables[slot], single);
					assertSame(other, drawables[1]); assertEquals(13, view.getCompoundDrawablePadding()); assertTrue(view.isChecked());
					assertEquals("Native row", view.getText().toString());
					if (slot >= 0) assertNull(view.getCheckMarkDrawable());
				}
			}
		});
	}
	@Test public void realGlyphPixelsStayVisibleOnLightDarkAmoledAndLowContrastCustomAccents() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create(); View owner = new View(context);
			for (int surface : new int[] {Color.WHITE, 0xff30363a, Color.BLACK, 0xff21030c}) {
				boolean dark = ColorUtils.calculateLuminance(surface) < .5;
				for (int accent : new int[] {Color.BLACK, Color.WHITE, 0xffe50038, surface}) {
					AppearanceColors palette = AppearanceColors.standardInterface(dark, surface, accent,
							dark ? Color.WHITE : Color.BLACK, Color.RED);
					int[] colors = ChoiceAppearance.colors(palette, surface);
					for (boolean radio : new boolean[] {false, true}) {
						ChoiceIndicatorDrawable drawable = new ChoiceIndicatorDrawable(owner, radio); drawable.setColors(colors);
						drawable.setBounds(0, 0, 24, 24); Bitmap pixels = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
						try {
							for (boolean checked : new boolean[] {false, true}) {
								drawable.setState(checked ? new int[] {android.R.attr.state_enabled, android.R.attr.state_checked}
										: new int[] {android.R.attr.state_enabled});
								Canvas canvas = new Canvas(pixels); canvas.drawColor(surface); drawable.draw(canvas);
								int actual = pixels.getPixel(radio ? 12 : checked ? 7 : 3, radio ? checked ? 12 : 3 : checked ? 7 : 12);
								assertEquals(255, Color.alpha(actual)); assertTrue(ColorUtils.calculateContrast(actual, surface) >= 3);
								if (checked && !radio) assertTrue(ColorUtils.calculateContrast(pixels.getPixel(10, 15), actual) >= 3);
							}
						} finally { pixels.recycle(); }
					}
				}
			}
		});
	}
	@Test public void actualTranslucentParentSurfaceIsCompositedAndMixedStateKeepsNativeSemantics() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create(); FrameLayout parent = new FrameLayout(context), card = new FrameLayout(context);
			parent.setBackgroundColor(Color.BLACK); card.setBackgroundColor(0x80ffffff); parent.addView(card);
			CheckBox view = new CheckBox(context); card.addView(view); ThemeEngine.applyStyle(view);
			AppearanceColors palette = StandardAppearance.colors(context);
			int actual = OpaqueColors.over(0x80ffffff, Color.BLACK);
			assertEquals(actual, ChoiceAppearance.colors(view, palette, null)[3]);
			ScopeCheckBox mixed = new ScopeCheckBox(context); card.addView(mixed); mixed.setMixed(true);
			int[] changes = {0}; mixed.setOnCheckedChangeListener((button, checked) -> changes[0]++);
			InterfaceAppearance.choiceIndicator(mixed); assertTrue(mixed.isMixed()); assertFalse(mixed.isChecked()); assertEquals(0, changes[0]);
			android.view.accessibility.AccessibilityNodeInfo info = mixed.createAccessibilityNodeInfo(); assertTrue(info.isCheckable());
			view.setEnabled(false); InterfaceAppearance.choiceIndicator(view); assertFalse(view.isEnabled()); glyph(view.getButtonDrawable(), false);
		});
	}
	@Test public void realSingleAndMultipleChoiceDialogsKeepCallbacksAndSelectionWithMaterialOffAndOn() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			Object[] previous = new Object[1]; AtomicReference<AlertDialog> dialog = new AtomicReference<>();
			scenario.onActivity(activity -> previous[0] = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION));
			try {
				for (boolean material : new boolean[] {false, true}) for (boolean single : new boolean[] {false, true}) {
					AtomicInteger clicks = new AtomicInteger();
					scenario.onActivity(activity -> {
						Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, material).close();
						MotionDialogBuilder builder = MotionDialogBuilder.standard(PostingTestContext.create(activity));
						if (single) builder.setSingleChoiceItems(new String[] {"One", "Two"}, 0, (d, which) -> clicks.incrementAndGet());
						else builder.setMultiChoiceItems(new String[] {"One", "Two"}, new boolean[] {true, false}, (d, which, checked) -> clicks.incrementAndGet());
						AlertDialog current = builder.setNegativeButton(android.R.string.cancel, null).create(); dialog.set(current); current.show();
					});
					InstrumentationRegistry.getInstrumentation().waitForIdleSync();
					scenario.onActivity(activity -> {
						ListView list = dialog.get().getListView(); CheckedTextView row = (CheckedTextView) list.getChildAt(0);
						Drawable marker = row.getCheckMarkDrawable();
						if (marker == null) { Drawable[] compound = row.getCompoundDrawablesRelative(); marker = compound[0] != null ? compound[0] : compound[2]; }
						glyph(marker, single); assertTrue(list.isItemChecked(0)); assertEquals(0, clicks.get());
						list.performItemClick(list.getChildAt(1), 1, list.getAdapter().getItemId(1));
						assertEquals(1, clicks.get()); assertTrue(list.isItemChecked(1));
						assertEquals(!single, list.isItemChecked(0)); dialog.get().dismiss(); dialog.set(null);
					});
				}
			} finally {
				scenario.onActivity(activity -> {
					if (dialog.get() != null) dialog.get().dismiss();
					if (previous[0] instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous[0]).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				});
			}
		}
	}
}
