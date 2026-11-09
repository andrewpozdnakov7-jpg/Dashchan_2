package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import static com.mishiranu.dashchan.widget.ExpandedThreadScrollState.Decision.*;
import org.junit.Test;

public class ExpandedThreadScrollStateTest {
	@Test public void forwardAndReverseRequireTwelvePixelsInTheirOwnDirection() {
		ExpandedThreadScrollState state = new ExpandedThreadScrollState(12);
		assertEquals(KEEP, state.scroll(6, false));
		assertEquals(KEEP, state.scroll(5, false));
		assertEquals(HIDE, state.scroll(1, false));
		assertEquals(KEEP, state.scroll(-5, false));
		assertEquals(SHOW, state.scroll(-7, false));
		assertEquals(KEEP, state.scroll(11, false));
		assertEquals(HIDE, state.scroll(1, false));
	}

	@Test public void layoutAndBottomContactNeverShowOrSynthesizeAReverse() {
		ExpandedThreadScrollState state = new ExpandedThreadScrollState(12);
		assertEquals(HIDE, state.scroll(900, false));
		for (int i = 0; i < 30; i++) assertEquals(KEEP, state.scroll(0, false));
		assertEquals(KEEP, state.scroll(-3, false));
		assertEquals(KEEP, state.scroll(0, false));
		assertEquals(KEEP, state.scroll(-8, false));
		assertEquals(SHOW, state.scroll(-1, false));
	}

	@Test public void idleOwnerOrLockerResetDropsPreviousDistance() {
		ExpandedThreadScrollState state = new ExpandedThreadScrollState(12);
		assertEquals(KEEP, state.scroll(8, false)); state.reset();
		assertEquals(KEEP, state.scroll(4, false));
		assertEquals(HIDE, state.scroll(8, false)); state.reset();
		assertEquals(KEEP, state.scroll(-11, false));
		assertEquals(SHOW, state.scroll(-1, false));
	}

	@Test public void actualTopAlwaysShowsAndResetsDirection() {
		ExpandedThreadScrollState state = new ExpandedThreadScrollState(12);
		assertEquals(HIDE, state.scroll(50, false));
		assertEquals(SHOW, state.scroll(0, true));
		assertEquals(KEEP, state.scroll(11, false));
		assertEquals(HIDE, state.scroll(1, false));
	}

	@Test public void largeConsumedMotionCannotOverflowAccumulation() {
		ExpandedThreadScrollState state = new ExpandedThreadScrollState(12);
		assertEquals(HIDE, state.scroll(Integer.MAX_VALUE, false));
		assertEquals(SHOW, state.scroll(Integer.MIN_VALUE, false));
	}
}
