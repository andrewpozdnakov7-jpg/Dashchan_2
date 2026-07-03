package com.mishiranu.dashchan.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

public class AudioFocus {
	public enum Change {LOSS, LOSS_TRANSIENT, GAIN}

	public interface Callback {
		void onChange(Change change);
	}

	private final AudioManager audioManager;
	private final AudioManager.OnAudioFocusChangeListener listener;
	private final AudioFocusRequest request;

	public AudioFocus(Context context, Callback callback) {
		audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		listener = focusChange -> {
			if (acquired) {
				Change change = null;
				switch (focusChange) {
					case AudioManager.AUDIOFOCUS_LOSS: {
						release();
						change = Change.LOSS;
						break;
					}
					case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
						change = Change.LOSS_TRANSIENT;
						break;
					}
					case AudioManager.AUDIOFOCUS_GAIN: {
						change = Change.GAIN;
						break;
					}
				}
				if (change != null) {
					callback.onChange(change);
				}
			}
		};
		request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
				.setAudioAttributes(new AudioAttributes.Builder()
						.setLegacyStreamType(AudioManager.STREAM_MUSIC).build())
				.setOnAudioFocusChangeListener(listener).build();
	}

	private boolean acquired = false;

	public boolean acquire() {
		if (!acquired && audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			acquired = true;
			return true;
		}
		return acquired;
	}

	public void release() {
		if (acquired) {
			acquired = false;
			audioManager.abandonAudioFocusRequest(request);
		}
	}
}
