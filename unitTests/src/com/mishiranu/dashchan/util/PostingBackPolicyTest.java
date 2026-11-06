package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class PostingBackPolicyTest {
	@Test public void disabledPredictiveAndOlderApisKeepNormalImePriority() {
		for (int api : new int[] {30, 32, 33, 34, 36, 37}) {
			assertFalse(PostingBackPolicy.canPrioritizeIme(api, false));
			assertFalse(PostingBackPolicy.isPredictiveGesture(api, false, 0, 0f, 100f));
		}
		for (int api : new int[] {30, 32, 33}) {
			assertFalse(PostingBackPolicy.canPrioritizeIme(api, true));
			assertFalse(PostingBackPolicy.isPredictiveGesture(api, true, 1, 400f, 100f));
		}
	}
	@Test public void bothActualEdgeGesturesRetainThePostingShortcut() {
		for (int api : new int[] {34, 35, 36, 37}) {
			assertTrue(PostingBackPolicy.canPrioritizeIme(api, true));
			for (int edge : new int[] {0, 1}) {
				boolean gesture = PostingBackPolicy.isPredictiveGesture(api, true, edge, 0f, 100f);
				assertTrue(gesture);
				assertFalse(PostingBackPolicy.shouldHideIme(true, gesture, true));
			}
		}
	}
	@Test public void buttonAndUnknownStartEventsCannotBecomePredictiveSwipes() {
		for (int edge : new int[] {2, -1, 100}) {
			assertFalse(PostingBackPolicy.isPredictiveGesture(36, true, edge, Float.NaN, Float.NaN));
			assertFalse(PostingBackPolicy.isPredictiveGesture(36, true, edge, 0f, 100f));
		}
		assertFalse(PostingBackPolicy.isPredictiveGesture(36, true, 0, Float.NaN, 100f));
		assertFalse(PostingBackPolicy.isPredictiveGesture(36, true, 1, 0f, Float.POSITIVE_INFINITY));
	}
	@Test public void ordinaryBackHidesTheKeyboardThenAllowsTheNextBackToNavigate() {
		assertTrue(PostingBackPolicy.shouldHideIme(true, false, true));
		assertFalse(PostingBackPolicy.shouldHideIme(true, false, false));
		assertFalse(PostingBackPolicy.shouldHideIme(true, true, false));
		assertTrue(PostingBackPolicy.shouldHideIme(false, true, true));
		assertTrue(PostingBackPolicy.shouldHideIme(false, false, true));
	}
}
