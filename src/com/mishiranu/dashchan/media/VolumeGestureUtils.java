package com.mishiranu.dashchan.media;

public final class VolumeGestureUtils {
	private VolumeGestureUtils() {}

	public static int calculateVolume(int startVolume, int maximumVolume,
			float distanceFraction, int sensitivity) {
		if (maximumVolume <= 0) {
			return 0;
		}
		int volume = startVolume + Math.round(distanceFraction * maximumVolume * sensitivity / 100f);
		return Math.max(0, Math.min(maximumVolume, volume));
	}
}
