package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class FastScrollProjectionTest {
	@Test public void appendedRowsKeepTheOldThumbPositionThenSettleWithoutOvershoot() {
		FastScrollProjection projection = new FastScrollProjection();
		projection.rebase(.013f, .034f, 1000, false);
		assertEquals(.034f, projection.project(.013f, 1000), .000001f);
		float previous = .034f;
		for (long time = 1000; time <= 1200; time++) {
			float current = projection.project(.013f, time);
			assertTrue(current <= previous + .000001f); assertTrue(current >= .013f - .000001f);
			previous = current;
		}
		assertEquals(.013f, projection.project(.013f, 1200), 0f);
		assertFalse(projection.isRunning(1200));
	}
	@Test public void drawingAndDraggingRemainInversesAndBothTrackEndsRemainExact() {
		FastScrollProjection projection = new FastScrollProjection();
		for (float target : new float[] {0f, .034f, .7f, 1f}) {
			projection.rebase(.013f, target, 1000, false);
			for (long time : new long[] {1000, 1030, 1100, 1199, 1200}) {
				float previous = -1f;
				for (int i = 0; i <= 1000; i++) {
					float raw = i / 1000f, shown = projection.project(raw, time);
					assertTrue(shown >= previous);
					// The extreme initial anchor can compress one branch near a single float ULP.
					if (target > 0f && target < 1f) assertEquals(raw, projection.unproject(shown, time), .00001f);
					previous = shown;
				}
				assertEquals(0f, projection.project(0f, time), 0f); assertEquals(1f, projection.project(1f, time), 0f);
				assertEquals(0f, projection.unproject(0f, time), 0f); assertEquals(1f, projection.unproject(1f, time), 0f);
			}
		}
	}
	@Test public void holdingTheThumbFreezesItsMappingAndReleaseResumesSettlement() {
		FastScrollProjection projection = new FastScrollProjection();
		projection.rebase(.2f, .6f, 1000, false);
		float shown = projection.project(.2f, 1050);
		projection.setFrozen(true, 1050);
		assertEquals(shown, projection.project(.2f, 9000), 0f);
		assertFalse(projection.isRunning(9000));
		assertEquals(.2f, projection.unproject(shown, 9000), .000001f);
		projection.setFrozen(false, 9000);
		assertEquals(shown, projection.project(.2f, 9000), 0f);
		assertEquals(.2f, projection.project(.2f, 9200), 0f);
	}
	@Test public void anotherAppendStartsAtTheCurrentlyVisiblePositionAndResetRestoresIdentity() {
		FastScrollProjection projection = new FastScrollProjection();
		projection.rebase(.2f, .6f, 1000, false);
		float shown = projection.project(.25f, 1080);
		projection.rebase(.1f, shown, 1080, true);
		assertEquals(shown, projection.project(.1f, 1080), .000001f);
		projection.reset(); assertEquals(.37f, projection.project(.37f, 1080), 0f);
		assertEquals(.37f, projection.unproject(.37f, 1080), 0f);
		projection.rebase(0f, .5f, 2000, false); assertEquals(.37f, projection.project(.37f, 2000), 0f);
	}
}
