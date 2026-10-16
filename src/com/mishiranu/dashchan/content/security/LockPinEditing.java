package com.mishiranu.dashchan.content.security;

import android.text.Editable;

/** Edits the existing protected field in-place; no second PIN buffer or key history. */
final class LockPinEditing {
	private LockPinEditing() {}
	static boolean edit(Editable value, int selectionStart, int selectionEnd, CharSequence digit) {
		int start = Math.max(0, Math.min(value.length(), selectionStart));
		int end = Math.max(0, Math.min(value.length(), selectionEnd));
		int from = Math.min(start, end), to = Math.max(start, end);
		if (digit == null) {
			if (from != to) value.delete(from, to);
			else if (from > 0) value.delete(from - 1, from);
			return false;
		}
		if (digit.length() != 1 || digit.charAt(0) < '0' || digit.charAt(0) > '9'
				|| value.length() - (to - from) >= 12) return false;
		value.replace(from, to, digit, 0, 1);
		return true;
	}
}
