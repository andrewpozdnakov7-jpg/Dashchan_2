package com.mishiranu.dashchan.content.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReplyPushContractTest {
	@Test
	public void createsCanonicalWatchId() {
		assertEquals("09ad40545764b394a654e2b49b77efd398eee3d8551ac6b23f9ab86c7d4b2c95",
				ReplyPushContract.makeWatchId("123e4567-e89b-42d3-a456-426614174000",
						"dvach", "mobi", "1234567", "1234999"));
	}

	@Test
	public void rejectsUnsupportedBoardWatchId() {
		assertEquals(null, ReplyPushContract.makeWatchId(
				"123e4567-e89b-42d3-a456-426614174000", "dvach", "b", "1234567", "1234999"));
	}

	@Test
	public void validatesCanonicalIdentifiers() {
		assertTrue(ReplyPushContract.isCanonicalPositiveNumber("9007199254740991"));
		assertFalse(ReplyPushContract.isCanonicalPositiveNumber("9007199254740992"));
		assertFalse(ReplyPushContract.isCanonicalPositiveNumber("01"));
		assertTrue(ReplyPushContract.isInstallationId("123e4567-e89b-42d3-a456-426614174000"));
		assertFalse(ReplyPushContract.isInstallationId("123E4567-E89B-42D3-A456-426614174000"));
	}

	@Test
	public void checksQuietHoursWithinOneDay() {
		int startMinute = 2 * 60;
		int endMinute = 9 * 60;
		assertFalse(ReplyPushContract.isMinuteInQuietHours(startMinute - 1, startMinute, endMinute));
		assertTrue(ReplyPushContract.isMinuteInQuietHours(startMinute, startMinute, endMinute));
		assertTrue(ReplyPushContract.isMinuteInQuietHours(endMinute - 1, startMinute, endMinute));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(endMinute, startMinute, endMinute));
	}

	@Test
	public void checksQuietHoursAcrossMidnight() {
		int startMinute = 22 * 60;
		int endMinute = 8 * 60;
		assertTrue(ReplyPushContract.isMinuteInQuietHours(23 * 60, startMinute, endMinute));
		assertTrue(ReplyPushContract.isMinuteInQuietHours(7 * 60 + 59, startMinute, endMinute));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(endMinute, startMinute, endMinute));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(12 * 60, startMinute, endMinute));
	}

	@Test
	public void rejectsEmptyOrInvalidQuietHours() {
		assertFalse(ReplyPushContract.isMinuteInQuietHours(2 * 60, 2 * 60, 2 * 60));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(-1, 2 * 60, 9 * 60));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(2 * 60, -1, 9 * 60));
		assertFalse(ReplyPushContract.isMinuteInQuietHours(2 * 60, 2 * 60, 24 * 60));
	}

	@Test
	public void calculatesIdentityResetCooldown() {
		long cooldown = 6L * 60L * 60L * 1000L;
		long now = 10L * cooldown;
		assertEquals(cooldown, ReplyPushContract.getCooldownRemaining(now, now, cooldown));
		assertEquals(cooldown / 2L,
				ReplyPushContract.getCooldownRemaining(now, now - cooldown / 2L, cooldown));
		assertEquals(0L, ReplyPushContract.getCooldownRemaining(now, now - cooldown, cooldown));
		assertEquals(0L, ReplyPushContract.getCooldownRemaining(now, 0L, cooldown));
		assertEquals(cooldown,
				ReplyPushContract.getCooldownRemaining(now, now + 1L, cooldown));
	}
}
