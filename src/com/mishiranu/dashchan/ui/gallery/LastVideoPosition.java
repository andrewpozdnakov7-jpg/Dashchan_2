package com.mishiranu.dashchan.ui.gallery;

/** One bookmark, not a history. The key is a digest, never a source URL or local path. */
final class LastVideoPosition {
	final String key;
	final long position;

	LastVideoPosition(String key, long position) {
		this.key = key;
		this.position = Math.max(0L, position);
	}

	static LastVideoPosition decode(String value) {
		if (value == null || value.length() < 66 || value.charAt(64) != ':') return null;
		String key = value.substring(0, 64);
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return null;
		}
		try {
			long position = Long.parseLong(value.substring(65));
			return position >= 0L ? new LastVideoPosition(key, position) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	String encode() {
		return key + ':' + position;
	}

	long resumePosition(String currentKey, long duration) {
		return key.equals(currentKey) && duration > 0L && position < duration ? position : 0L;
	}

	static LastVideoPosition capture(String key, long position, long duration, boolean completed) {
		return new LastVideoPosition(key, completed || duration <= 0L || position >= duration ? 0L : position);
	}
}
