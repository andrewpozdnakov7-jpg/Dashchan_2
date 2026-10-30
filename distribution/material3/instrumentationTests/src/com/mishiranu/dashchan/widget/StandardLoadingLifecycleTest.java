package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class StandardLoadingLifecycleTest {
	@Test public void drawableKeepsSquareBoundsAndOneOwnerAndNeverRunsDetached() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context c = PostingTestContext.create(); ProgressBar a = new ProgressBar(c), b = new ProgressBar(c);
			StandardLoadingDrawable x = new StandardLoadingDrawable(a), y = new StandardLoadingDrawable(b);
			a.setIndeterminateDrawable(x); b.setIndeterminateDrawable(y);
			assertNotSame(x, y); assertSame(a, x.getCallback()); assertSame(b, y.getCallback());
			x.setBounds(0, 0, 300, 70); Rect bounds = x.indicatorBounds();
			assertEquals(bounds.width(), bounds.height()); assertTrue(bounds.width() <= x.getIntrinsicWidth());
			x.start(); assertFalse(x.isRunning()); x.stop(); x.stop(); assertFalse(x.isRunning());
		});
	}
	@Test public void visibilityAndDetachStopActualOwnedDrawableWithoutDialogListeners() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> {
				Context c = PostingTestContext.create(a); FrameLayout host = new FrameLayout(c);
				ProgressBar view = new ProgressBar(c); view.setIndeterminate(true);
				StandardLoadingDrawable d = new StandardLoadingDrawable(view); view.setIndeterminateDrawable(d);
				host.addView(view, new FrameLayout.LayoutParams(120, 120)); a.setContentView(host);
				d.start(); view.setVisibility(View.GONE); d.sync(); assertFalse(d.isRunning());
				view.setVisibility(View.VISIBLE); d.start(); host.removeView(view); d.sync(); assertFalse(d.isRunning());
				host.addView(view); d.start(); d.stop(); assertFalse(d.isRunning());
			});
		}
	}
}
