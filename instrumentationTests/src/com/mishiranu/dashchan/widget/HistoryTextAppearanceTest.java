package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Detached native geometry fixtures; no history database or network requests. */
@RunWith(AndroidJUnit4.class)
public class HistoryTextAppearanceTest {
	private Context context() {
		Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(target.getResources().getConfiguration()); config.fontScale = 1.3f;
		return ThemeEngine.attach(target.createConfigurationContext(config));
	}
	private void withInterface(boolean modern, Runnable run) {
		Object old = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		try {
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, modern).close(); run.run();
		} finally {
			if (old instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) old).close();
			else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
		}
	}
	private ViewFactory.TwoLinesViewHolder row() {
		LinearLayout parent = new LinearLayout(context());
		ViewFactory.TwoLinesViewHolder row = ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_TEXT2_END, true);
		row.text1.setText("A long thread title from the browsing history");
		row.text2.setText("A board title and forum name which both need to remain readable");
		row.text2End.setText("November 6, 2026");
		return row;
	}
	private void layout(View view) {
		int width = Math.round(320 * view.getResources().getDisplayMetrics().density);
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, width, view.getMeasuredHeight());
	}
	@Test public void rebindingDoesNotCompoundFontsAndDefaultRestoresTheOriginalGeometry() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean modern : new boolean[]{false, true}) withInterface(modern, () -> {
				ViewFactory.TwoLinesViewHolder row = row();
				InterfaceAppearance.serviceRow(row.view, row.text1, row.text2, row.text2End); layout(row.view);
				float title = row.text1.getTextSize(), metadata = row.text2.getTextSize(), date = row.text2End.getTextSize();
				int height = row.view.getMeasuredHeight(), maxLines = row.text1.getMaxLines();
				for (int percent : new int[]{200, 200, 500, 30, 200}) {
					HistoryTextAppearance.row(row, percent);
					assertEquals(title * percent / 100, row.text1.getTextSize(), .01f);
					assertEquals(metadata * percent / 100, row.text2.getTextSize(), .01f);
					assertEquals(date * percent / 100, row.text2End.getTextSize(), .01f);
				}
				HistoryTextAppearance.row(row, 100); layout(row.view);
				assertEquals(title, row.text1.getTextSize(), .01f);
				assertEquals(maxLines, row.text1.getMaxLines()); assertEquals(height, row.view.getMeasuredHeight());
			});
		});
	}
	@Test public void largeMetadataStacksWithoutOverlapInBothInterfacesAndDirections() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean modern : new boolean[]{false, true}) withInterface(modern, () -> {
				for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
					ViewFactory.TwoLinesViewHolder row = row(); row.view.setLayoutDirection(direction);
					HistoryTextAppearance.row(row, 500); layout(row.view);
					LinearLayout metadata = (LinearLayout) row.text2.getParent();
					assertEquals(LinearLayout.VERTICAL, metadata.getOrientation());
					assertTrue(row.text2.getWidth() > 0); assertTrue(row.text2.getLineCount() > 1);
					assertTrue(row.text2End.getTop() >= row.text2.getBottom());
					assertTrue(row.text2End.getBottom() <= metadata.getHeight());
					assertTrue(row.text2End.getWidth() <= metadata.getWidth());
				}
			});
		});
	}
	@Test public void sectionHeaderGrowsAndShrinksWithoutClippingOrAccumulatingScale() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TextView header = ViewFactory.makeListTextHeader(new LinearLayout(context()));
			header.setText("Earlier this week"); layout(header);
			float size = header.getTextSize(); int height = header.getMeasuredHeight();
			for (int percent : new int[]{500, 200, 500}) {
				HistoryTextAppearance.header(header, percent); layout(header);
				assertEquals(size * percent / 100, header.getTextSize(), .01f);
				assertTrue(header.getHeight() >= header.getLayout().getHeight() + header.getCompoundPaddingTop()
						+ header.getCompoundPaddingBottom());
			}
			HistoryTextAppearance.header(header, 100); layout(header);
			assertEquals(size, header.getTextSize(), .01f); assertEquals(height, header.getMeasuredHeight());
		});
	}
}
