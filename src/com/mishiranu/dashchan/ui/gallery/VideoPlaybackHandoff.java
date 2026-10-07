package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.os.SystemClock;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.util.AudioFocus;
import com.mishiranu.dashchan.util.ConcurrentUtils;

/** Main-thread, bounded ownership of playback while a gallery window is recreated. */
final class VideoPlaybackHandoff {
	private final VideoPlayer player;
	private final long started = SystemClock.elapsedRealtime();
	private AudioFocus focus;
	private boolean stopped;
	private final Runnable timeout = () -> stop("timeout");

	VideoPlaybackHandoff(Context context, VideoPlayer player, AudioFocus source, String reason) {
		this.player = player;
		focus = AudioFocus.forVideo(context, change -> {
			if (change != AudioFocus.Change.GAIN) stop("focus_loss");
		});
		source.transferTo(focus);
		ConcurrentUtils.HANDLER.postDelayed(timeout, 1500L);
		VideoDiagnostics.recordUi("playback_handoff begin reason=" + reason + " playing=" + player.isPlaying());
	}

	void adopt(AudioFocus target) {
		focus.transferTo(target);
		focus = target;
		VideoDiagnostics.recordUi("playback_handoff adopted playing=" + player.isPlaying());
	}

	boolean isPlaying() {
		return !stopped && player.isPlaying();
	}

	void finish() {
		ConcurrentUtils.HANDLER.removeCallbacks(timeout);
		VideoDiagnostics.recordUi("playback_handoff complete elapsed_ms="
				+ (SystemClock.elapsedRealtime() - started) + " playing=" + player.isPlaying());
	}

	void stop(String reason) {
		ConcurrentUtils.HANDLER.removeCallbacks(timeout);
		if (!stopped) {
			stopped = true;
			player.setPlaying(false);
			focus.release();
			VideoDiagnostics.recordUi("playback_handoff stopped reason=" + reason);
		}
	}
}
