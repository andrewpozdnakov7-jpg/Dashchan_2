package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CaptchaChoiceLayoutTest {
	private static boolean inside(View child, View parent) {
		for (ViewParent p = child.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
		return false;
	}
	private static void layout(View v, int w, int h) {
		v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h);
	}
	@Test public void wideBoundsAndShortWindowDoNotShrinkKeysBelowTouchSize() {
		assertTrue(CaptchaChoiceLayout.wide(800, 340, 560)); assertFalse(CaptchaChoiceLayout.wide(400, 800, 560));
		assertFalse(CaptchaChoiceLayout.wide(500, 310, 560));
		assertEquals(64, CaptchaChoiceLayout.cellSize(300, 208, 3, 3, 8, 48, 80));
		assertEquals(48, CaptchaChoiceLayout.cellSize(310, 104, 3, 3, 8, 48, 80));
	}
	@Test public void bothPanesAreVisibleAndSwitchingBackKeepsTheSameSelectionViews() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
			float d = c.getResources().getDisplayMetrics().density;
			LinearLayout info = new LinearLayout(c); info.setOrientation(LinearLayout.VERTICAL);
			View title = new View(c), slots = new View(c), task = new View(c);
			info.addView(title, -1, Math.round(36*d)); info.addView(slots, -1, Math.round(64*d));
			info.addView(task, -1, Math.round(120*d));
			LinearLayout grid = new LinearLayout(c); grid.setOrientation(LinearLayout.VERTICAL); info.addView(grid,-1,-2);
			View chosen = null;
			for (int i=0;i<3;i++) {
				LinearLayout row = new LinearLayout(c); grid.addView(row,-1,-2);
				for (int j=0;j<3;j++) {
					FrameLayout cell = new FrameLayout(c) {
						@Override protected void onMeasure(int w, int h) {
							int side = MeasureSpec.getSize(w); super.onMeasure(w, MeasureSpec.makeMeasureSpec(side,MeasureSpec.EXACTLY));
						}
					};
					LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1f);
					if (j<2) lp.rightMargin = Math.round(8*d); if (i<2) lp.bottomMargin = Math.round(8*d);
					row.addView(cell,lp); if (i==1 && j==1) { chosen=cell; cell.setSelected(true); }
				}
			}
			CaptchaChoiceLayout body = new CaptchaChoiceLayout(c,info,title,slots,task,grid);
			body.split(true);
			layout(body, Math.round(640 * d), Math.round(224 * d));
			assertFalse(inside(grid,info)); assertTrue(inside(task,info));
			assertTrue(task.getHeight() >= Math.round(64*d)); assertTrue(task.getBottom() <= body.getHeight());
			assertTrue(grid.getHeight() <= body.getHeight()); assertTrue(grid.getWidth() < body.getWidth()/2);
			assertNotNull(chosen); assertTrue(chosen.getWidth() >= Math.round(48*d)); assertTrue(chosen.isSelected());
			int shortHeight = Math.round(56 * d);
			layout(body, Math.round(640 * d), shortHeight);
			assertEquals(shortHeight, task.getHeight());
			assertTrue(grid.getHeight() > shortHeight);
			assertTrue(chosen.getWidth() >= Math.round(48 * d));
			assertTrue(chosen.isSelected());
			body.split(false);
			layout(body, Math.round(308 * d), Math.round(480 * d));
			assertSame(info,grid.getParent()); assertTrue(chosen.isSelected()); assertEquals(Math.round(120*d),task.getHeight());
		});
	}
}
