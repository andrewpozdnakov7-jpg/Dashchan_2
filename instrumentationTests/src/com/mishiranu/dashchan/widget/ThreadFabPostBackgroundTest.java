package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Full-width post background/touch fixtures; no forum or posting/network. Prepared, not run. */
public class ThreadFabPostBackgroundTest {
	private static PostLinearLayout row() {
		PostLinearLayout row = new PostLinearLayout(InstrumentationRegistry.getInstrumentation().getTargetContext());
		row.setOrientation(LinearLayout.VERTICAL); row.setPaddingRelative(8, 6, 10, 7);
		row.setBackgroundColor(Color.DKGRAY);
		View content = new View(row.getContext()); content.setBackgroundColor(Color.BLUE);
		row.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20));
		return row;
	}
	private static void layout(PostLinearLayout row) {
		row.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY));
		row.layout(0, 0, 200, 80);
	}
	@Test public void ownPostBackgroundAndLiveHighlightCoverTheFullRow() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostLinearLayout row = row(); row.setPostMark(PostLinearLayout.MARK_USER_POST, Color.MAGENTA);
			layout(row); assertEquals(200, row.getWidth());
			assertEquals(182, row.getChildAt(0).getWidth());
			Bitmap pixels = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888);
			try {
				row.draw(new Canvas(pixels)); assertEquals(Color.DKGRAY, pixels.getPixel(180, 60));
				row.setSecondaryBackgroundColor(0x80ffff00); pixels.eraseColor(Color.TRANSPARENT); row.draw(new Canvas(pixels));
				assertEquals(pixels.getPixel(60, 60), pixels.getPixel(180, 60));
				row.setSecondaryBackground(null); pixels.eraseColor(Color.TRANSPARENT); row.draw(new Canvas(pixels));
				assertEquals(Color.DKGRAY, pixels.getPixel(180, 60));
			} finally { pixels.recycle(); }
		});
	}
	@Test public void fullWidthAndBasePaddingArePreservedAcrossLayoutsAndRtl() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostLinearLayout row = row(); layout(row); layout(row);
			assertEquals(10, row.getPaddingEnd()); assertEquals(182, row.getChildAt(0).getWidth());
			row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); layout(row);
			assertEquals(10, row.getPaddingLeft()); assertEquals(8, row.getPaddingRight());
			assertEquals(182, row.getChildAt(0).getWidth());
		});
	}
	private static boolean touch(PostLinearLayout row, int action, float x) {
		long now = SystemClock.uptimeMillis(); MotionEvent e = MotionEvent.obtain(now, now, action, x, 40f, 0);
		try { return row.dispatchTouchEvent(e); } finally { e.recycle(); }
	}
	@Test public void uncoveredEndOfPostRemainsATapTargetInEitherDirection() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PostLinearLayout row = row(); row.setOnClickListener(v -> {}); layout(row);
			assertTrue(touch(row, MotionEvent.ACTION_DOWN, 180f)); touch(row, MotionEvent.ACTION_CANCEL, 180f);
			assertTrue(touch(row, MotionEvent.ACTION_DOWN, 60f)); touch(row, MotionEvent.ACTION_CANCEL, 60f);
			row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); layout(row);
			assertTrue(touch(row, MotionEvent.ACTION_DOWN, 20f)); touch(row, MotionEvent.ACTION_CANCEL, 20f);
			assertTrue(touch(row, MotionEvent.ACTION_DOWN, 100f)); touch(row, MotionEvent.ACTION_CANCEL, 100f);
		});
	}
}
