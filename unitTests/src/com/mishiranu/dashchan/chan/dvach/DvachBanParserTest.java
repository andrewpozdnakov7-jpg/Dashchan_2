package com.mishiranu.dashchan.chan.dvach;

import static org.junit.Assert.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.Test;

public class DvachBanParserTest {
	private static final String PREFIX = "Постинг запрещён. Бан: 1234567. Причина: ";
	private static final long EXPIRY = LocalDateTime.of(2026, 7, 16, 0, 0)
			.toInstant(ZoneOffset.ofHours(3)).toEpochMilli();

	private static void assertDetails(String text, String message, long expiry) {
		DvachBanParser.Details details = DvachBanParser.parse(PREFIX + text);
		assertNotNull(details);
		assertEquals("1234567", details.id);
		assertEquals(message, details.message);
		assertEquals(expiry, details.expireDate);
	}

	@Test public void extractsExpiryBeforeRemovingServiceMarker() {
		assertDetails("Тестовая причина (subnet) //!mobi. Чтв Июл 16 00:00:00 2026",
				"Тестовая причина (subnet)", EXPIRY);
	}

	@Test public void extractsExpiryWithoutServiceMarker() {
		assertDetails("Тестовая причина Чтв Июл 16 00:00:00 2026", "Тестовая причина", EXPIRY);
	}

	@Test public void extractsExpiryWithoutWeekday() {
		assertDetails("Тестовая причина //!test. Июл 16 00:00:00 2026", "Тестовая причина", EXPIRY);
	}

	@Test public void trimsWhitespaceAfterExpiry() {
		assertDetails("Тестовая причина //!b. Чтв Июл 16 00:00:00 2026 \n",
				"Тестовая причина", EXPIRY);
	}

	@Test public void preservesReasonWhenExpiryIsAbsent() {
		assertDetails("Тестовая причина", "Тестовая причина", 0L);
		assertDetails("Тестовая причина //!mobi.", "Тестовая причина", 0L);
	}

	@Test public void doesNotInventExpiryForPermanentBan() {
		assertDetails("Тестовая причина //!b. Бессрочно", "Тестовая причина", 0L);
	}

	@Test public void rejectsImpossibleCalendarDate() {
		assertDetails("Тестовая причина //!b. Фев 31 00:00:00 2026", "Тестовая причина", 0L);
	}

	@Test public void rejectsIncompleteDate() {
		assertDetails("Тестовая причина //!b. Июл 16 00:00:00", "Тестовая причина", 0L);
	}

	@Test public void preservesUnrecognizedSuffixWithoutMarker() {
		String text = "Тестовая причина: дата неизвестна";
		assertDetails(text, text, 0L);
	}

	@Test public void parsesAllServerMonthNames() {
		String[] months = {"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг",
				"Сен", "Окт", "Ноя", "Дек"};
		for (int month = 1; month <= months.length; month++) {
			long expiry = LocalDateTime.of(2026, month, 16, 12, 34, 56)
					.toInstant(ZoneOffset.ofHours(3)).toEpochMilli();
			assertDetails("Тестовая причина //!test. " + months[month - 1] + " 16 12:34:56 2026",
					"Тестовая причина", expiry);
		}
	}

	@Test public void leavesMalformedEnvelopesForRawMessageFallback() {
		assertNull(DvachBanParser.parse(null));
		assertNull(DvachBanParser.parse(""));
		assertNull(DvachBanParser.parse("Постинг запрещён"));
		assertNull(DvachBanParser.parse("Бан: invalid. Причина: Тестовая причина"));
		assertNull(DvachBanParser.parse("Бан: . Причина: Тестовая причина"));
		assertNull(DvachBanParser.parse("Бан: 1234567. Тестовая причина"));
	}
}
