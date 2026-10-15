package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import org.junit.Test;

public class PinRetryPolicyTest {
	@Test public void escalationIsBoundedAndStartsAtTheFifthFailure() {
		assertEquals(0, PinRetryPolicy.delayForFailures(4));
		assertEquals(30_000, PinRetryPolicy.delayForFailures(5));
		assertEquals(60_000, PinRetryPolicy.delayForFailures(6));
		assertEquals(900_000, PinRetryPolicy.delayForFailures(30));
	}
	@Test public void monotonicDeadlineExpiresAndCannotBecomeAnUnboundedWait() {
		assertEquals(30_000, PinRetryPolicy.remaining(40_000, 10_000));
		assertEquals(0, PinRetryPolicy.remaining(40_000, 40_000));
		assertEquals(0, PinRetryPolicy.remaining(40_000, 50_000));
		assertEquals(900_000, PinRetryPolicy.remaining(10_000_000, 10_000));
	}
}
