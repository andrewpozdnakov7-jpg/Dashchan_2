package com.mishiranu.dashchan.content;

/** Internal RAM/negative-cache key; network and on-disk attachment keys remain unchanged. */
final class ImageMemoryKey {
	private ImageMemoryKey() {}

	static String create(String chanName, int targetSize, String key, boolean resource, long generation) {
		return chanName + "\n" + (resource ? "res:" + generation + "\n" : "") + targetSize + "\n" + key;
	}
}
