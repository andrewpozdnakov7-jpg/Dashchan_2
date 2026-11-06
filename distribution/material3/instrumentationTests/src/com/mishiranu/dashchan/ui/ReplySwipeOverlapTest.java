package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.posting.PostingFragment;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.PostingGestureTestActivity;
import com.mishiranu.dashchan.widget.PreviewMemoryPolicy;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

/** Pixel ownership and failure fallback; does not send posts or request a real IME. */
public class ReplySwipeOverlapTest {
	private static Object field(Object owner, String name) {
		try {
			java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true); return field.get(owner);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static final class Source extends FrameLayout {
		boolean failCapture;
		Source(android.content.Context context) { super(context); }
		@Override public void draw(Canvas canvas) {
			if (failCapture) throw new IllegalStateException("fixture capture failure");
			super.draw(canvas);
		}
	}
	private static final class Fixture implements AutoCloseable {
		final FrameLayout host, list;
		final Source source;
		final TextView row;
		final ReplySwipeOverlap overlap;
		final ReplySwipeOverlap.Clock clock;
		int ticks;
		Fixture(PostingGestureTestActivity activity) {
			android.content.Context context = ThemeEngine.attach(activity);
			host = new FrameLayout(context); source = new Source(context); list = new FrameLayout(context);
			row = new TextView(context); row.setText("Reply source"); row.setBackgroundColor(android.graphics.Color.TRANSPARENT);
			list.addView(row, new FrameLayout.LayoutParams(-1, 240));
			source.addView(list, new FrameLayout.LayoutParams(-1, -1));
			host.addView(source, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(host);
			overlap = new ReplySwipeOverlap(host); clock = new ReplySwipeOverlap.Clock(() -> ticks++);
		}
		void ready() {
			Assume.assumeTrue(InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled());
			Assume.assumeFalse(PreviewMemoryPolicy.isMemoryLow(host.getContext()));
			assertTrue(source.isAttachedToWindow()); assertTrue(row.getWidth() > 0);
			row.setTranslationX(-24f); clock.start();
		}
		@Override public void close() { overlap.close(); clock.stop(); }
	}
	@Test public void transferredPixelsSurviveSourceDetachAndCleanupKeepsDestinationProperties() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			AtomicReference<Fixture> fixture = new AtomicReference<>();
			scenario.onActivity(activity -> fixture.set(new Fixture(activity)));
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			try {
				scenario.onActivity(activity -> {
					Fixture f = fixture.get(); f.ready(); PostingFragment destination = new PostingFragment();
					assertTrue(f.overlap.prepare(f.source, f.list, f.row, destination, f.clock, -24f));
					Object session = field(f.overlap, "active");
					Bitmap scene = (Bitmap) field(session, "scene"), card = (Bitmap) field(session, "card");
					assertEquals(255, android.graphics.Color.alpha(card.getPixel(0, 0)));
					assertEquals(View.VISIBLE, f.row.getVisibility());
					f.host.removeView(f.source);
					FrameLayout form = new FrameLayout(activity); form.setAlpha(.8f); form.setTranslationY(7f);
					f.host.addView(form, new FrameLayout.LayoutParams(-1, -1));
					f.overlap.install(destination); f.overlap.bind(destination, form);
					assertSame(session, field(f.overlap, "active"));
					assertFalse(scene.isRecycled()); assertFalse(card.isRecycled());
					f.overlap.finish(); f.overlap.finish();
					assertNull(field(f.overlap, "active"));
					assertFalse(((ValueAnimator) field(f.clock, "animator")).isRunning());
					assertEquals(.8f, form.getAlpha(), 0f); assertEquals(7f, form.getTranslationY(), 0f);
				});
			} finally { scenario.onActivity(activity -> fixture.get().close()); }
		}
	}
	@Test public void failedSceneCaptureRestoresLiveRowAndKeepsTheOriginalClockCallback() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			AtomicReference<Fixture> fixture = new AtomicReference<>();
			scenario.onActivity(activity -> fixture.set(new Fixture(activity)));
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			try {
				scenario.onActivity(activity -> {
					Fixture f = fixture.get(); f.ready(); f.source.failCapture = true;
					try { assertFalse(f.overlap.prepare(f.source, f.list, f.row, new PostingFragment(), f.clock, -24f)); }
					finally { f.source.failCapture = false; }
					assertNull(field(f.overlap, "active")); assertEquals(View.VISIBLE, f.row.getVisibility());
					assertEquals(-24f, f.row.getTranslationX(), 0f);
					int before = f.ticks;
					((ValueAnimator) field(f.clock, "animator")).setCurrentPlayTime(100);
					assertTrue(f.ticks > before);
				});
			} finally { scenario.onActivity(activity -> fixture.get().close()); }
		}
	}
}
