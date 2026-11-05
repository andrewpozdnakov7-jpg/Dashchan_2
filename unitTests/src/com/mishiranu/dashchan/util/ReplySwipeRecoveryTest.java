package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReplySwipeRecoveryTest {
	@Test public void successfulAndCanceledRecoveriesLeaveTheClampOnTheirFirstFrame() {
		Object owner = new Object();
		for (int sign : new int[]{-1, 1}) {
			ReplySwipeRecovery success = new ReplySwipeRecovery(owner, "post", 1, 300 * sign, 1000 * sign, 56 * sign, true);
			assertEquals(56 * sign, success.offset(300 * sign), .001f);
			assertEquals(42 * sign, success.offset(475 * sign), .001f);
			assertEquals(28 * sign, success.offset(650 * sign), .001f);
			assertEquals(0, success.offset(1000 * sign), .001f);
			ReplySwipeRecovery canceled = new ReplySwipeRecovery(owner, "post", 1, 300 * sign, 0, 56 * sign, false);
			assertEquals(42 * sign, canceled.offset(225 * sign), .001f);
			assertEquals(28 * sign, canceled.offset(150 * sign), .001f);
			assertEquals(0, canceled.offset(0), .001f);
			assertFalse(canceled.consume(owner, "post", 1));
		}
	}
	@Test public void onlyOneConfirmedReplyCanConsumeTheCapturedPost() {
		Object owner = new Object();
		ReplySwipeRecovery swipe = new ReplySwipeRecovery(owner, "123", 9, -56, -400, -56, true);
		assertTrue(swipe.consume(owner, new String("123"), 9));
		assertFalse(swipe.consume(owner, "123", 9));
	}
	@Test public void aChangedPostPageOrDatasetCannotReviveAnOldGesture() {
		Object owner = new Object();
		for (int mismatch = 0; mismatch < 3; mismatch++) {
			ReplySwipeRecovery swipe = new ReplySwipeRecovery(owner, "123", 9, 50, 400, 50, true);
			assertFalse(swipe.consume(mismatch == 0 ? new Object() : owner, mismatch == 1 ? "456" : "123", mismatch == 2 ? 10 : 9));
			assertFalse(swipe.consume(owner, "123", 9));
		}
	}
	@Test public void explicitCancelWinsOverASuccessfulHelperClassification() {
		Object owner = new Object();
		ReplySwipeRecovery swipe = new ReplySwipeRecovery(owner, "123", 9, 50, 400, 50, true);
		swipe.cancel(); assertEquals(0, swipe.offset(200), 0);
		assertFalse(swipe.consume(owner, "123", 9));
	}
	@Test public void zeroLengthReturnCanConfirmWithoutWaitingAndInvalidGeometryCannot() {
		Object owner = new Object();
		ReplySwipeRecovery immediate = new ReplySwipeRecovery(owner, "123", 9, 50, 50, 50, true);
		assertEquals(0, immediate.offset(50), 0); assertTrue(immediate.consume(owner, "123", 9));
		ReplySwipeRecovery invalid = new ReplySwipeRecovery(owner, "123", 9, Float.NaN, 50, 50, true);
		assertEquals(0, invalid.offset(50), 0); assertFalse(invalid.consume(owner, "123", 9));
	}
}
