package com.mishiranu.dashchan.ui.preference;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Arrays;
import org.junit.Test;

/** Detached measured fixtures; no StatisticsStorage reads, preference writes or clearing user data. */
public class StatisticsCardLayoutTest {
	private Context context(float scale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(base.getResources().getConfiguration());
		config.fontScale = scale;
		Context context = ThemeEngine.attach(new ContextThemeWrapper(base.createConfigurationContext(config),
				android.R.style.Theme_Material_Light));
		ThemeEngine.applyTheme(context);
		return context;
	}
	private void measure(View view, int widthDp) {
		int width = Math.round(widthDp * view.getResources().getDisplayMetrics().density);
		view.forceLayout();
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	private void assertInside(View view) {
		assertFalse(view instanceof HorizontalScrollView);
		assertEquals(0, view.getScrollX());
		if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
			TextView text = (TextView) view;
			assertNotNull(text.getLayout());
			assertNull(text.getEllipsize());
			for (int i = 0; i < text.getLineCount(); i++) assertEquals(0, text.getLayout().getEllipsisCount(i));
			assertEquals(text.length(), text.getLayout().getLineEnd(text.getLineCount() - 1));
			assertTrue(text.getHeight() >= text.getLayout().getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				View child = group.getChildAt(i);
				if (child.getVisibility() == View.GONE) continue;
				assertTrue(child.getLeft() >= 0); assertTrue(child.getRight() <= group.getWidth());
				assertTrue(child.getTop() >= 0); assertTrue(child.getBottom() <= group.getHeight());
				assertInside(child);
			}
		}
	}
	@Test public void ordinaryPhoneShowsFourValuesInTwoRowsWithReadableLabels() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			StatisticsCardView card = new StatisticsCardView(context(1));
			card.bind("fixture.example", new String[]{"1782", "2499", "3", "19"}, "Replies counted since 02.11.2026");
			measure(card, 320); assertInside(card);
			assertEquals(card.metrics.getChildAt(0).getTop(), card.metrics.getChildAt(1).getTop());
			assertTrue(card.metrics.getChildAt(2).getTop() >= card.metrics.getChildAt(0).getBottom());
			assertEquals("19", card.metrics.values[3].getText().toString());
			assertEquals(View.VISIBLE, card.period.getVisibility());
		});
	}
	@Test public void largeFontAndLongCountersStackWithoutTruncationIncludingRtl() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				StatisticsCardView card = new StatisticsCardView(context(2)); card.setLayoutDirection(direction);
				card.bind("a-long-forum-title.example", new String[]{Long.toString(Long.MAX_VALUE), "2499", "—", "19"},
						"Replies counted since 02.11.2026");
				measure(card, 280); assertInside(card);
				for (int i = 1; i < 4; i++)
					assertTrue(card.metrics.getChildAt(i).getTop() >= card.metrics.getChildAt(i - 1).getBottom());
			}
		});
	}
	@Test public void rebindingOverviewAndForumKeepsAllCountersAndResetsPeriodVisibility() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1);
			StatisticsFragment.Adapter adapter = new StatisticsFragment.Adapter(Arrays.asList(
					new StatisticsFragment.Adapter.ListItem("Total", "1782", "2499", "3", "19"),
					new StatisticsFragment.Adapter.ListItem("fixture.example", "84", "—", "—", "0")), "Replies counted since 02.11.2026");
			StatisticsFragment.Adapter.ViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
			adapter.onBindViewHolder(holder, 0); measure(holder.card, 320);
			assertEquals(View.VISIBLE, holder.card.period.getVisibility());
			adapter.onBindViewHolder(holder, 1); measure(holder.card, 320); assertInside(holder.card);
			assertEquals(View.GONE, holder.card.period.getVisibility());
			assertEquals("fixture.example", holder.card.title.getText().toString());
			assertEquals("84", holder.card.metrics.values[0].getText().toString());
			assertEquals("—", holder.card.metrics.values[1].getText().toString());
			assertEquals("0", holder.card.metrics.values[3].getText().toString());
			adapter.onBindViewHolder(holder, 0); measure(holder.card, 320); assertInside(holder.card);
			assertEquals(View.VISIBLE, holder.card.period.getVisibility());
		});
	}
	@Test public void appearanceRefreshAndRepeatedResizePreserveCompleteWrappedPeriod() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			StatisticsCardView card = new StatisticsCardView(context(2));
			String period = "Replies counted since 02.11.2026, independent of the overall statistics period";
			card.bind("fixture.example", new String[]{"1782", "2499", "3", "19"}, period);
			for (int width : new int[]{280, 600, 320, 280}) {
				InterfaceAppearance.refreshTree(card); measure(card, width); assertInside(card);
				assertEquals(period, card.period.getText().toString());
				assertFalse(card.period.isSingleLine());
				assertEquals(Integer.MAX_VALUE, card.period.getMaxLines());
			}
		});
	}
}
