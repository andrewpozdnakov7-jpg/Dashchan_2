package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import org.junit.Test;

/** Prepared geometry fixtures only; no video, downloads, preference writes or activity launches. */
public class PlayerButtonsGridTest {
	@Test public void firstFourStayAtTheBottomAndFifthSixthStartTheRowAbove() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int cell = Math.round(48 * context.getResources().getDisplayMetrics().density);
			ArrayList<View> buttons = buttons(context);
			PlayerButtonsGrid grid = new PlayerButtonsGrid(context, buttons, 4, 48);
			measure(grid, cell * 4);
			assertEquals(cell * 2, grid.getMeasuredHeight());
			for (int i = 0; i < 4; i++) {
				assertEquals(i * cell, buttons.get(i).getLeft()); assertEquals(cell, buttons.get(i).getTop());
			}
			assertEquals(0, buttons.get(4).getLeft()); assertEquals(0, buttons.get(4).getTop());
			assertEquals(cell, buttons.get(5).getLeft()); assertEquals(0, buttons.get(5).getTop());
		});
	}
	@Test public void narrowResizeKeepsIdentityParentAndTargetSize() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			int cell = Math.round(48 * context.getResources().getDisplayMetrics().density);
			ArrayList<View> buttons = buttons(context);
			PlayerButtonsGrid grid = new PlayerButtonsGrid(context, buttons, 6, 48);
			measure(grid, cell * 6);
			measure(grid, cell * 2);
			assertEquals(cell * 3, grid.getMeasuredHeight());
			for (int i = 0; i < buttons.size(); i++) {
				View button = buttons.get(i);
				assertSame(button, grid.getChildAt(i)); assertSame(grid, button.getParent());
				assertEquals(cell, button.getWidth()); assertEquals(cell, button.getHeight());
				assertEquals((i % 2) * cell, button.getLeft());
				assertEquals((2 - i / 2) * cell, button.getTop());
			}
		});
	}
	private static ArrayList<View> buttons(Context context) {
		ArrayList<View> result = new ArrayList<>();
		for (int i = 0; i < 6; i++) result.add(new View(context));
		return result;
	}
	private static void measure(View view, int width) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
}
