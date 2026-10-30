package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Touch ownership only; the phone acceptance pass must also inspect the actual transition pixels. */
@RunWith(AndroidJUnit4.class)
public class ThreadMotionLayoutTest {
	@Test public void cancellationReachesAnExistingChildWhileNewTouchesAreBlocked() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ThreadMotionLayout host = new ThreadMotionLayout(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
			int[] cancelled = {0};
			host.addView(new View(host.getContext()) {
				@Override public boolean onTouchEvent(MotionEvent event) {
					if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelled[0]++;
					return true;
				}
			}, new android.widget.FrameLayout.LayoutParams(100, 100));
			host.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
			host.layout(0, 0, 100, 100);
			MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
			MotionEvent cancel = MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 50, 50, 0);
			try {
				assertTrue(host.dispatchTouchEvent(down)); host.setMotionBlocking(true);
				host.dispatchTouchEvent(cancel); assertEquals(1, cancelled[0]);
			} finally { down.recycle(); cancel.recycle(); }
		});
	}
	@Test public void hiddenUntransformedTargetsCannotReceiveTouchesAndCleanupUnblocksThem() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ThreadMotionLayout host = new ThreadMotionLayout(
					InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
			int[] touches = {0};
			View child = new View(host.getContext()) {
				@Override public boolean onTouchEvent(MotionEvent event) { touches[0]++; return true; }
			};
			host.addView(child, new android.widget.FrameLayout.LayoutParams(100, 100));
			host.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
			host.layout(0, 0, 100, 100);
			host.setMotionBlocking(true);
			MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
			try {
				assertTrue(host.dispatchTouchEvent(down)); assertEquals(0, touches[0]);
				host.setMotionBlocking(false); host.setMotionBlocking(false);
				assertTrue(host.dispatchTouchEvent(down)); assertEquals(1, touches[0]);
			} finally { down.recycle(); }
		});
	}
}
