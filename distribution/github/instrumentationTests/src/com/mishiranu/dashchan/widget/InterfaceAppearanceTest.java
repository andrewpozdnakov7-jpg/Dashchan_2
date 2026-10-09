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
	private Context context() { return ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
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
