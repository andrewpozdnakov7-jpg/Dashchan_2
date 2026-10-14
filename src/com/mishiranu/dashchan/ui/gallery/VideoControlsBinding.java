package com.mishiranu.dashchan.ui.gallery;

/** Gallery HUD ownership survives a video-to-video decoder rebind, not a media-type change. */
final class VideoControlsBinding {
	private boolean retained;

	void select(boolean playableVideo, boolean playerReady, boolean modernControls) {
		// Selection and loading can interrupt twice before the new decoder is ready.
		retained = modernControls && playableVideo && (playerReady || retained);
	}
	boolean retainsControls() { return retained; }
	boolean visible(boolean playerReady, boolean transferred, boolean systemUiVisible) {
		return (playerReady || retained) && !transferred && systemUiVisible;
	}
	void complete() { retained = false; }
	void clear() { retained = false; }
}
