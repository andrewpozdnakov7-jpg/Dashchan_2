package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;

/** Prepared UI geometry tests, without real player or preference changes. */
public class PlayerButtonsPreviewPanelTest {
	@Test public void roundedPanelWrapsColumnsInsideTheFullWidthDropZone() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			float density = context.getResources().getDisplayMetrics().density;
			int cell = Math.round(48 * density), padding = Math.round(4 * density);
			for (int columns = 1; columns <= 4; columns++) {
				FrameLayout zone = new FrameLayout(context);
				PlayerButtonsPreviewPanel panel = new PlayerButtonsPreviewPanel(context, buttons(context, 4), columns);
				zone.addView(panel, new FrameLayout.LayoutParams(-2, -2));
				measure(zone, cell * 6, View.MeasureSpec.EXACTLY);
				assertEquals(cell * 6, zone.getMeasuredWidth());
				assertEquals(columns * cell + 2 * padding, panel.getMeasuredWidth());
				assertEquals(((4 + columns - 1) / columns) * cell + 2 * padding, panel.getMeasuredHeight());
			}
		});
	}

	@Test public void narrowerAvailableWidthAddsRowsWithoutShrinkingButtons() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			float density = context.getResources().getDisplayMetrics().density;
			int cell = Math.round(48 * density), padding = Math.round(4 * density);
			ArrayList<View> buttons = buttons(context, 6);
			PlayerButtonsPreviewPanel panel = new PlayerButtonsPreviewPanel(context, buttons, 6);
			measure(panel, 2 * cell + 2 * padding, View.MeasureSpec.AT_MOST);
			assertEquals(2 * cell + 2 * padding, panel.getWidth());
			assertEquals(3 * cell + 2 * padding, panel.getHeight());
			for (View button : buttons) {
				assertEquals(cell, button.getWidth());
				assertEquals(cell, button.getHeight());
			}
		});
	}

	@Test public void emptyPanelRemainsVisibleAsADropDestination() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int minimum = Math.round(56 * context.getResources().getDisplayMetrics().density);
			PlayerButtonsPreviewPanel panel = new PlayerButtonsPreviewPanel(context, buttons(context, 0), 2);
			measure(panel, minimum * 3, View.MeasureSpec.AT_MOST);
			assertEquals(minimum, panel.getWidth());
			assertEquals(minimum, panel.getHeight());
		});
	}

	private static ArrayList<View> buttons(Context context, int count) {
		ArrayList<View> result = new ArrayList<>();
		for (int i = 0; i < count; i++) result.add(new View(context));
		return result;
	}
	private static void measure(View view, int width, int mode) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, mode),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
}
