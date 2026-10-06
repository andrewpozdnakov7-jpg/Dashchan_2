package com.mishiranu.dashchan.chan.dvach;

import java.text.DateFormatSymbols;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Parses ban details without depending on Android or the posting request. */
final class DvachBanParser {
	private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
			ThreadLocal.withInitial(DvachBanParser::createDateFormat);

	static final class Details {
		final String id;
		final String message;
		final long expireDate;

		Details(String id, String message, long expireDate) {
			this.id = id;
			this.message = message;
			this.expireDate = expireDate;
		}
	}

	private DvachBanParser() {}

	static Details parse(String reason) {
		if (reason == null || reason.isEmpty()) return null;
		int idStart = reason.indexOf(": ");
		if (idStart < 0) return null;
		idStart += 2;
		int idEnd = reason.indexOf(". ", idStart);
		if (idEnd < 0 || !isAsciiDigits(reason, idStart, idEnd)) return null;
		int messageStart = reason.indexOf(": ", idEnd + 2);
		if (messageStart < 0) return null;
		messageStart += 2;
		String details = reason.substring(messageStart).trim();

		// The server can place the expiry AFTER the //!board. service marker.
		// Extract it from the complete details before removing that marker.
		long expireDate = 0L;
		int earliestDateStart = Math.max(0, details.length() - 32);
		for (int start = earliestDateStart; start < details.length(); start++) {
			if (start > 0 && !Character.isWhitespace(details.charAt(start - 1))) continue;
			String candidate = details.substring(start);
			ParsePosition position = new ParsePosition(0);
			Date date = DATE_FORMAT.get().parse(candidate, position);
			if (date != null && position.getIndex() == candidate.length()) {
				expireDate = date.getTime();
				int messageEnd = start;
				if (start >= 4 && details.charAt(start - 1) == ' ' &&
						Character.isUpperCase(details.charAt(start - 4)) &&
						Character.isLowerCase(details.charAt(start - 3)) &&
						Character.isLowerCase(details.charAt(start - 2))) {
					messageEnd = start - 4;
				}
				details = details.substring(0, messageEnd).trim();
				break;
			}
		}
		int marker = details.indexOf("//!");
		if (marker >= 0) {
			int markerEnd = details.indexOf('.', marker + 3);
			if (markerEnd > marker + 3) details = details.substring(0, marker).trim();
		}
		return new Details(reason.substring(idStart, idEnd), details, expireDate);
	}

	private static boolean isAsciiDigits(String value, int start, int end) {
		if (start >= end) return false;
		for (int i = start; i < end; i++) {
			char c = value.charAt(i);
			if (c < '0' || c > '9') return false;
		}
		return true;
	}

	private static SimpleDateFormat createDateFormat() {
		DateFormatSymbols symbols = new DateFormatSymbols(Locale.ROOT);
		symbols.setShortMonths(new String[] {"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг",
				"Сен", "Окт", "Ноя", "Дек"});
		SimpleDateFormat format = new SimpleDateFormat("MMM dd HH:mm:ss yyyy", Locale.ROOT);
		format.setDateFormatSymbols(symbols);
		format.setTimeZone(TimeZone.getTimeZone("GMT+3"));
		format.setLenient(false);
		return format;
	}
}
