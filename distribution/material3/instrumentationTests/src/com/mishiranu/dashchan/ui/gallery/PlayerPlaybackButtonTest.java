package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.ImageButton;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared native regression fixtures; not an executed device test. */
@RunWith(AndroidJUnit4.class)
public class PlayerPlaybackButtonTest {
	@Test public void retainedGlyphIsWhiteWhileRealButtonAndAccessibilityStayDisabled() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			ImageButton button = new ImageButton(context); button.setImageResource(R.drawable.ic_pause);
			PlayerControlsStyle.button(button, context.getResources().getDisplayMetrics().density, false);
			int[] clicks = {0}; button.setOnClickListener(view -> clicks[0]++);
			int padding = button.getPaddingLeft(); button.setAlpha(.8f);
			PlayerControlsStyle.playbackReadiness(button, true); button.setEnabled(false);
			assertFalse(button.isEnabled()); assertFalse(button.createAccessibilityNodeInfo().isEnabled());
			assertEquals(Color.WHITE, button.getImageTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT));
			assertEquals(padding, button.getPaddingLeft()); assertEquals(.8f, button.getAlpha(), 0f);
			long now = SystemClock.uptimeMillis();
			MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 16f, 16f, 0);
			MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 16f, 16f, 0);
			try { button.dispatchTouchEvent(down); button.dispatchTouchEvent(up); }
			finally { down.recycle(); up.recycle(); }
			assertEquals(0, clicks[0]);
			PlayerControlsStyle.playbackReadiness(button, false); button.setEnabled(true);
			assertEquals(Color.WHITE, button.getImageTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT));
			button.performClick(); assertEquals(1, clicks[0]);
		});
	}

	@Test public void nonRetainedUnavailableButtonStillUsesItsOriginalDisabledAppearance() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			ImageButton button = new ImageButton(context);
			PlayerControlsStyle.button(button, context.getResources().getDisplayMetrics().density, true);
			button.setEnabled(false); PlayerControlsStyle.playbackReadiness(button, false);
			assertEquals(0x73ffffff, button.getImageTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT));
			PlayerControlsStyle.playbackReadiness(button, true);
			assertEquals(Color.WHITE, button.getImageTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT));
			PlayerControlsStyle.playbackReadiness(button, false);
			assertEquals(0x73ffffff, button.getImageTintList().getColorForState(button.getDrawableState(), Color.TRANSPARENT));
			assertFalse(button.isEnabled());
		});
	}
}
