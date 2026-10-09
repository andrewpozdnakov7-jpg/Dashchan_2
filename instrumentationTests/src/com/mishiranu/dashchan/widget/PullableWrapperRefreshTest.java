package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Refresh semantics must be identical in both distributions and independent of animation lifecycle. */
@RunWith(AndroidJUnit4.class)
public class PullableWrapperRefreshTest {
	private static final class Host extends View implements PullableWrapper.Wrapped {
		final boolean top;
		Host(boolean top) {
			super(ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()));
			this.top = top; layout(0, 0, 400, 1000);
		}
		@Override public EdgeEffectHandler getEdgeEffectHandler() { return null; }
		@Override public boolean isScrolledToTop() { return top; }
		@Override public boolean isScrolledToBottom() { return !top; }
		@Override public int getEdgeEffectShift(EdgeEffectHandler.Side side) { return 0; }
	}
	private static final class Session {
		final Host host; final PullableWrapper wrapper;
		final List<PullableWrapper.Side> pulls = new ArrayList<>();
		final List<Boolean> busy = new ArrayList<>();
		Session(boolean top) {
			host = new Host(top); wrapper = new PullableWrapper(host);
			wrapper.setPullSides(PullableWrapper.Side.BOTH);
			wrapper.setOnPullListener((source, side) -> pulls.add(side));
			wrapper.setPullStateListener((source, value) -> busy.add(value));
		}
		void event(int action, float y) {
			MotionEvent event = MotionEvent.obtain(1, 2, action, 200f, y, 0);
			try { wrapper.onTouchEventOrNull(event); } finally { event.recycle(); }
		}
		void drag(float distance, int release) {
			float start = host.top ? 100f : 900f;
			event(MotionEvent.ACTION_DOWN, start);
			event(MotionEvent.ACTION_MOVE, start + (host.top ? distance : -distance));
			event(release, start + (host.top ? distance : -distance));
		}
	}
	@Test public void thresholdRefreshesTheCorrectEdgeOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean top : new boolean[] {true, false}) {
				Session s = new Session(top); s.drag(500f, MotionEvent.ACTION_UP);
				assertEquals(1, s.pulls.size());
				assertEquals(top ? PullableWrapper.Side.TOP : PullableWrapper.Side.BOTTOM, s.pulls.get(0));
				assertEquals(List.of(true), s.busy);
				s.drag(500f, MotionEvent.ACTION_UP); assertEquals(1, s.pulls.size());
				s.wrapper.cancelBusyState(); assertEquals(List.of(true, false), s.busy);
			}
		});
	}
	@Test public void shortPullAndCancelledGestureNeverRefresh() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean top : new boolean[] {true, false}) {
				Session shortPull = new Session(top); shortPull.drag(30f, MotionEvent.ACTION_UP);
				Session cancelled = new Session(top); cancelled.drag(500f, MotionEvent.ACTION_CANCEL);
				assertTrue(shortPull.pulls.isEmpty()); assertTrue(shortPull.busy.isEmpty());
				assertTrue(cancelled.pulls.isEmpty()); assertTrue(cancelled.busy.isEmpty());
			}
		});
	}
	@Test public void pausingPresentationDoesNotCompleteOrRestartTheRequest() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Session s = new Session(false); s.drag(500f, MotionEvent.ACTION_UP);
			s.wrapper.setHostActive(false); s.wrapper.setHostActive(true); s.wrapper.setHostActive(false);
			assertEquals(1, s.pulls.size()); assertEquals(List.of(true), s.busy);
			s.wrapper.cancelBusyState(); s.wrapper.cancelBusyState();
			assertEquals(List.of(true, false), s.busy);
		});
	}
	@Test public void programmaticBothDoesNotInventAnEdgeGesture() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Session s = new Session(true); s.wrapper.startBusyState(PullableWrapper.Side.BOTH);
			s.wrapper.setHostActive(false); s.wrapper.setHostActive(true);
			assertTrue(s.pulls.isEmpty()); assertEquals(List.of(true), s.busy);
			s.wrapper.cancelBusyState(); assertEquals(List.of(true, false), s.busy);
		});
	}
}
