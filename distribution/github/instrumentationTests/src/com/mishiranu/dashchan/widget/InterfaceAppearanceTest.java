package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared regression fixtures; no persisted preferences or device build are changed. */
@RunWith(AndroidJUnit4.class)
public class InterfaceAppearanceTest {
	private Context context() { return PostingTestContext.create(); }
	@Test public void repeatedCompatibilityApplyThenDisableRestoresOriginalState() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Button view = new Button(context()); view.setTextSize(19); view.setLineSpacing(5, 1.1f);
			view.setPaddingRelative(3, 4, 5, 6); view.setMinimumHeight(23);
			android.graphics.Typeface typeface = view.getTypeface(); float size = view.getTextSize();
			android.text.method.TransformationMethod transformation = view.getTransformationMethod();
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.ACTION;
			int[] clicks = {0}; view.setOnClickListener(v -> clicks[0]++);
			InterfaceAppearance.renderResolved(view, state, true);
			int height = view.getMinimumHeight(), padding = view.getPaddingStart();
			android.graphics.drawable.Drawable first = view.getBackground();
			InterfaceAppearance.renderResolved(view, state, true);
			assertSame(first, view.getBackground()); assertEquals(height, view.getMinimumHeight());
			assertEquals(padding, view.getPaddingStart()); assertEquals(typeface, view.getTypeface());
			assertEquals(5f, view.getLineSpacingExtra(), .01f); assertEquals(1.1f, view.getLineSpacingMultiplier(), .01f);
			InterfaceAppearance.renderResolved(view, state, false); view.performClick();
			assertEquals(size, view.getTextSize(), .01f); assertSame(transformation, view.getTransformationMethod());
			assertEquals(23, view.getMinimumHeight()); assertEquals(3, view.getPaddingStart());
			assertEquals(6, view.getPaddingBottom()); assertEquals(1, clicks[0]);
		});
	}
	@Test public void resourceAttributeChangeWithSameThemeObjectInvalidatesResolvedPalette() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(); Button view = new Button(context);
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.ACTION;
			ThemeEngine.Theme theme = ThemeEngine.getTheme(context);
			context.getTheme().applyStyle(android.R.style.Theme_Material_Light, true);
			InterfaceAppearance.renderResolved(view, state, true);
			AppearanceColors before = state.appliedColors;
			context.getTheme().applyStyle(android.R.style.Theme_Material, true);
			InterfaceAppearance.renderResolved(view, state, true);
			assertSame(theme, ThemeEngine.getTheme(context)); assertNotEquals(before, state.appliedColors);
		});
	}
	@Test public void customThemeForegroundAlwaysMeetsRequestedContrast() {
		int[] colors = {Color.WHITE, Color.BLACK, Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE, 0xff777777, 0xff1c1c1c};
		for (int background : colors) for (int requested : colors) {
			assertTrue(ColorUtils.calculateContrast(InterfaceAppearance.contrasting(requested, background, 4.5), background) >= 4.5);
			assertTrue(ColorUtils.calculateContrast(InterfaceAppearance.contrasting(requested, background, 3), background) >= 3);
		}
	}
	@Test public void restoringFieldDoesNotRollBackInputSelectionOrValidationSettings() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText view = new EditText(context()); view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
			InputFilter[] filters = {new InputFilter.LengthFilter(100)}; view.setFilters(filters); view.setText("draft");
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.FIELD;
			view.setText("new draft"); view.setSelection(2, 5); view.setBackground(new ColorDrawable(Color.RED)); state.restore(view);
			assertEquals("new draft", view.getText().toString()); assertEquals(2, view.getSelectionStart()); assertEquals(5, view.getSelectionEnd());
			assertSame(filters[0], view.getFilters()[0]); assertTrue((view.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
		});
	}
	@Test public void restoringSliderKeepsLatestValueAndDoesNotSeek() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			SeekBar view = new SeekBar(context()); view.setMax(1000); view.setProgress(100);
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.SLIDER;
			view.setProgress(750); int[] calls = {0}; view.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean user) { calls[0]++; }
				@Override public void onStartTrackingTouch(SeekBar seekBar) {}
				@Override public void onStopTrackingTouch(SeekBar seekBar) {}
			});
			view.setThumb(new ColorDrawable(Color.BLUE)); state.restore(view);
			assertEquals(1000, view.getMax()); assertEquals(750, view.getProgress()); assertEquals(0, calls[0]);
		});
	}
	@Test public void borderlessPostingColorsKeepFillDistinctAndTextReadable() {
		int[] colors = {Color.WHITE, Color.BLACK, Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE, 0xffeeeeee, 0xff242424};
		for (int window : colors) for (int accent : colors) {
			int background = InterfaceAppearance.postingBackground(window);
			assertEquals(255, Color.alpha(background));
			int fill = InterfaceAppearance.lighterPostingFill(ColorUtils.blendARGB(background, Color.WHITE,
					ColorUtils.calculateLuminance(background) < .5 ? .10f : .80f), background);
			int focused = InterfaceAppearance.lighterPostingFill(ColorUtils.blendARGB(fill, accent, .06f), background);
			int error = InterfaceAppearance.lighterPostingFill(ColorUtils.blendARGB(fill, Color.RED, .08f), background);
			for (int color : new int[] {fill, focused, error}) {
				assertEquals(255, Color.alpha(color));
				assertTrue(ColorUtils.calculateLuminance(color) > ColorUtils.calculateLuminance(background));
				assertTrue(ColorUtils.calculateContrast(color, background) >= 1.08);
			}
			for (int text : colors) {
				assertTrue(ColorUtils.calculateContrast(InterfaceAppearance.contrasting(text, fill, 4.5), fill) >= 4.5);
				assertTrue(ColorUtils.calculateContrast(InterfaceAppearance.contrasting(text, focused, 4.5), focused) >= 4.5);
				assertTrue(ColorUtils.calculateContrast(InterfaceAppearance.contrasting(text, error, 4.5), error) >= 4.5);
			}
		}
	}
	@Test public void restoringPostingFormKeepsTheOriginalLocalBackdropAndLatestBounds() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			LinearLayout view = new LinearLayout(context()); ColorDrawable original = new ColorDrawable(Color.WHITE);
			view.setBackground(original); view.setPadding(3, 4, 5, 6);
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.POSTING_FORM;
			view.setBackground(new ColorDrawable(Color.GRAY)); view.setLayoutParams(new LinearLayout.LayoutParams(200, 300));
			state.restore(view);
			assertSame(original, view.getBackground()); assertEquals(Color.WHITE, original.getColor());
			assertEquals(200, view.getLayoutParams().width); assertEquals(300, view.getLayoutParams().height);
			assertEquals(3, view.getPaddingLeft()); assertEquals(4, view.getPaddingTop()); assertEquals(5, view.getPaddingRight()); assertEquals(6, view.getPaddingBottom());
		});
	}
	@Test public void restoringPostingFieldRestoresOnlyItsSpacingWithoutRollingBackEditorBounds() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText view = new EditText(context());
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(120, 180);
			params.setMargins(3, 4, 5, 6); view.setLayoutParams(params);
			InterfaceAppearance.State state = new InterfaceAppearance.State(view);
			state.role = InterfaceAppearance.Role.POSTING_FIELD;
			view.setText("latest draft"); view.setSelection(3); view.setMinLines(7); view.setMaxLines(11);
			params.width = 200; params.height = 300; params.setMargins(9, 10, 11, 14);
			state.restore(view);
			assertSame(params, view.getLayoutParams()); assertEquals(200, params.width); assertEquals(300, params.height);
			assertEquals(9, params.leftMargin); assertEquals(10, params.topMargin); assertEquals(11, params.rightMargin);
			assertEquals(6, params.bottomMargin); assertEquals(7, view.getMinLines()); assertEquals(11, view.getMaxLines());
			assertEquals("latest draft", view.getText().toString()); assertEquals(3, view.getSelectionStart());
		});
	}
	@Test public void restoringSwitchDoesNotChangeTheCheckedValueOrFireAChange() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Switch view = new Switch(context()); InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.SWITCH;
			view.setChecked(true); int[] calls = {0}; view.setOnCheckedChangeListener((button, checked) -> calls[0]++);
			view.setTrackDrawable(new ColorDrawable(Color.RED)); state.restore(view);
			assertTrue(view.isChecked()); assertEquals(0, calls[0]);
		});
	}
	@Test public void restoringButtonKeepsClickHandlerAndOriginalSurfaceAndPadding() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Button view = new Button(context()); ColorDrawable original = new ColorDrawable(Color.GREEN);
			view.setBackground(original); view.setBackgroundTintList(ColorStateList.valueOf(Color.GREEN)); view.setPaddingRelative(3, 4, 5, 6);
			InterfaceAppearance.State state = new InterfaceAppearance.State(view); state.role = InterfaceAppearance.Role.ACTION;
			int[] clicks = {0}; view.setOnClickListener(v -> clicks[0]++); view.setBackground(new ColorDrawable(Color.RED)); view.setPadding(20, 20, 20, 20);
			state.restore(view); view.performClick(); assertEquals(1, clicks[0]); assertSame(original, view.getBackground());
			assertEquals(3, view.getPaddingStart()); assertEquals(4, view.getPaddingTop()); assertEquals(5, view.getPaddingEnd()); assertEquals(6, view.getPaddingBottom());
		});
	}
}
