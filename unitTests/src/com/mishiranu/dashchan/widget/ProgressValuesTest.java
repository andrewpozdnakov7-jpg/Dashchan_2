package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class ProgressValuesTest {
	@Test public void invalidTotalsNeverProduceInfinityOrFakeCompletion() {
		ProgressValues v = new ProgressValues(); v.value = 7;
		for (int max : new int[]{0, -1, Integer.MIN_VALUE}) {
			v.max = max; assertFalse(v.hasTotal()); assertEquals(0d, v.fraction(), 0); assertEquals(0, v.level());
		}
	}
	@Test public void valueBeforeMaxRetainsTheRealCounter() {
		ProgressValues v = new ProgressValues(); v.value = 150; v.max = 0;
		assertFalse(v.hasTotal()); v.max = 300;
		assertEquals(.5d, v.fraction(), 0); assertEquals(150, v.value); assertEquals(5000, v.level());
	}
	@Test public void maxBeforeValueAndChangingTotalDoNotGenerateEventsOrChangeValues() {
		ProgressValues v = new ProgressValues(); v.max = 20; v.value = 5;
		assertEquals(.25d, v.fraction(), 0); v.max = 10;
		assertEquals(.5d, v.fraction(), 0); assertEquals(5, v.value);
	}
	@Test public void switchingModeAndReturningPreservesRawState() {
		ProgressValues v = new ProgressValues(); v.value = 3; v.max = 4; v.indeterminate = true;
		assertFalse(v.hasTotal()); assertEquals(0d, v.fraction(), 0);
		v.indeterminate = false; assertEquals(.75d, v.fraction(), 0);
	}
	@Test public void clampOnlyPresentationWithoutIntegerOverflow() {
		ProgressValues v = new ProgressValues(); v.max = 1; v.value = Integer.MAX_VALUE;
		assertEquals(10000, v.level()); assertEquals(Integer.MAX_VALUE, v.value);
		v.value = -10; assertEquals(0, v.level()); assertEquals(-10, v.value);
	}
	@Test public void completionAndResetHaveNoArtificialDelay() {
		ProgressValues v = new ProgressValues(); v.max = 10; v.value = 10;
		assertEquals(10000, v.level()); v.value = 0; assertEquals(0, v.level());
	}
}
