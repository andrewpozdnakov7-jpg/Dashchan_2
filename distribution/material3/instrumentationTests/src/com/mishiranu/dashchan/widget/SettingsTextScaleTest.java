package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached settings rows only: no preference writes, Activity, network or user data. */
@RunWith(AndroidJUnit4.class)
public class SettingsTextScaleTest {
	private void resolve(View view, boolean material) {
		Object state = view.getTag(R.id.interface_appearance_state);
		if (state instanceof InterfaceAppearance.State) {
			InterfaceAppearance.renderResolved(view, (InterfaceAppearance.State) state, material);
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) resolve(group.getChildAt(i), material);
		}
		StandardAppearance.resolveLayout(view, material);
	}

	private void bind(ViewFactory.TwoLinesViewHolder row, boolean material) {
		SettingsTextAppearance.restore(row.text1, row.text2);
		InterfaceAppearance.settingsRow(row.view, row.text1, row.text2, row.widgetFrame, false);
		resolve(row.view, material);
	}

	@Test public void repeatedBindingAndPreviewChangesNeverCompoundTheScale() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			for (boolean material : new boolean[] {false, true}) {
				ViewFactory.TwoLinesViewHolder row = ViewFactory.makeTwoLinesListItem(new FrameLayout(context),
						ViewFactory.FEATURE_MULTILINE_TITLE);
				row.text1.setText("Setting"); row.text2.setText("Description");
				bind(row, material);
				float base = row.text1.getTextSize();
				int lines = row.text1.getMaxLines();
				for (int i = 0; i < 20; i++) {
					bind(row, material);
					SettingsTextAppearance.apply(200, row.text1, row.text2);
					SettingsTextAppearance.apply(200, row.text1, row.text2);
					assertEquals(base * 2, row.text1.getTextSize(), .01f);
				}
				SettingsTextAppearance.apply(30, row.text1, row.text2);
				assertEquals(base * .3f, row.text1.getTextSize(), .01f);
				SettingsTextAppearance.apply(500, row.text1, row.text2);
				assertEquals(base * 5, row.text1.getTextSize(), .01f);
				SettingsTextAppearance.apply(100, row.text1, row.text2);
				assertEquals(base, row.text1.getTextSize(), .01f);
				assertEquals(lines, row.text1.getMaxLines());
			}
		});
	}

	@Test public void largeSettingsTextWrapsInBothThemesWithoutResizingTheControl() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
			for (float systemScale : new float[] {1f, 2f}) for (boolean material : new boolean[] {false, true}) {
				Configuration configuration = new Configuration(base.getResources().getConfiguration());
				configuration.fontScale = systemScale;
				Context context = PostingTestContext.create(base.createConfigurationContext(configuration));
				ViewFactory.TwoLinesViewHolder row = ViewFactory.makeTwoLinesListItem(new FrameLayout(context),
						ViewFactory.FEATURE_WIDGET | ViewFactory.FEATURE_MULTILINE_TITLE);
				row.text1.setText("Размер текста настроек / Settings text size");
				row.text2.setText("Длинное описание настройки должно переноситься целиком. A long setting description must stay readable.");
				CheckBox control = new CheckBox(context);
				row.widgetFrame.addView(control); row.widgetFrame.setVisibility(View.VISIBLE);
				float controlText = control.getTextSize();
				for (int percent : new int[] {150, 200, 500}) {
					bind(row, material);
					SettingsTextAppearance.apply(percent, row.text1, row.text2);
					int width = Math.round(240 * context.getResources().getDisplayMetrics().density);
					row.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
					row.view.layout(0, 0, row.view.getMeasuredWidth(), row.view.getMeasuredHeight());
					for (TextView text : new TextView[] {row.text1, row.text2}) {
						assertNotNull(text.getLayout());
						assertEquals(text.length(), text.getLayout().getLineEnd(text.getLineCount() - 1));
						assertTrue(text.getHeight() >= text.getLayout().getHeight()
								+ text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
						assertNull(text.getEllipsize());
					}
					assertEquals(controlText, control.getTextSize(), .01f);
				}
			}
		});
	}
}
