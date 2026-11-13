package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.ThemeEngine;
import org.junit.Assume;
import org.junit.Test;

/** Real presentation binding, without an encoder, network, media files or posting. */
public class VideoConversionBadgeTest {
	private static VideoConversionBadge badge() {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		ThemeEngine.applyTheme(context);
		return new VideoConversionBadge(context, VideoConversionSetup.materialContext(context), new FrameLayout(context));
	}
	@Test public void expiredAcceptedResultDoesNotReplayAfterRecreationAndErrorsStayVisible() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoConversionBadge badge = badge();
			try {
				badge.bind("old:file", VideoConversionBadge.Stage.SUCCESS, 100, "done", "done", SystemClock.uptimeMillis() - 2000);
				assertEquals(View.GONE, badge.getVisibility());
				badge.bind("new:file", VideoConversionBadge.Stage.ERROR, -1, "error", "error", 0);
				assertEquals(View.VISIBLE, badge.getVisibility());
				badge.bind("new:file", VideoConversionBadge.Stage.ERROR, -1, "error", "error", 0);
				assertEquals(View.VISIBLE, badge.getVisibility());
				badge.release(); assertEquals(View.GONE, badge.getVisibility());
			} finally { badge.release(); }
		});
	}
	@Test public void oldSuccessTimeoutCannotHideANewAttemptOrAnError() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Assume.assumeTrue(InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0);
			VideoConversionBadge badge = badge();
			try {
				badge.bind("first:file", VideoConversionBadge.Stage.SUCCESS, 100, "done", "done", SystemClock.uptimeMillis());
				java.lang.reflect.Field expiry = VideoConversionBadge.class.getDeclaredField("expiry"); expiry.setAccessible(true);
				Runnable old = (Runnable) expiry.get(badge); assertNotNull(old);
				badge.bind("second:file", VideoConversionBadge.Stage.PROGRESS, 8, "8%", "progress", 0);
				old.run(); assertEquals(View.VISIBLE, badge.getVisibility());
				badge.bind("second:file", VideoConversionBadge.Stage.ERROR, -1, "error", "error", 0);
				old.run(); assertEquals(View.VISIBLE, badge.getVisibility());
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			finally { badge.release(); }
		});
	}
	@Test public void hidingStatusRestoresDecorationWithoutControllerRebind() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoConversionBadge badge = badge(); View marker = new View(badge.getContext());
			try {
				badge.setVisibilityChanged(() -> marker.setAlpha(badge.getVisibility() == View.VISIBLE ? 0f : 1f));
				badge.bind("file", VideoConversionBadge.Stage.ERROR, -1, "error", "error", 0);
				assertEquals(0f, marker.getAlpha(), 0f);
				badge.bind("file", VideoConversionBadge.Stage.SUCCESS, 100, "done", "done", SystemClock.uptimeMillis() - 2000);
				assertEquals(View.GONE, badge.getVisibility()); assertEquals(1f, marker.getAlpha(), 0f);
				badge.bind("next", VideoConversionBadge.Stage.PROGRESS, 10, "10%", "progress", 0);
				assertEquals(0f, marker.getAlpha(), 0f); badge.release(); assertEquals(1f, marker.getAlpha(), 0f);
			} finally { badge.release(); }
		});
	}

}
