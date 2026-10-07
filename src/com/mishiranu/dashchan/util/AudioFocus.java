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
	private final Callback callback;
	private final int focusGain;
	private Session session;

	public AudioFocus(Context context, Callback callback) {
		this(context, AudioManager.AUDIOFOCUS_GAIN, callback);
	}

	/** Video briefly interrupts the previous player; release allows it to regain focus. */
	public static AudioFocus forVideo(Context context, Callback callback) {
		return new AudioFocus(context, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, callback);
	}

	private AudioFocus(Context context, int focusGain, Callback callback) {
		audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
		this.callback = callback;
		this.focusGain = focusGain;
	}

	// A request follows the playing media, not the window. All operations use the main thread.
	private static final class Session {
		final AudioManager audioManager;
		final AudioFocusRequest request;
		Callback callback;
		boolean acquired;

		Session(AudioManager audioManager, int focusGain, Callback callback) {
			this.audioManager = audioManager;
			this.callback = callback;
			AudioManager.OnAudioFocusChangeListener listener = focusChange -> {
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
						this.callback.onChange(change);
					}
				}
			};
			request = new AudioFocusRequest.Builder(focusGain)
					.setAudioAttributes(new AudioAttributes.Builder()
							.setLegacyStreamType(AudioManager.STREAM_MUSIC).build())
					.setOnAudioFocusChangeListener(listener).build();
		}

		void release() {
			if (acquired) {
				acquired = false;
				audioManager.abandonAudioFocusRequest(request);
			}
		}
	}

	public boolean acquire() {
		if (session == null) session = new Session(audioManager, focusGain, callback);
		if (!session.acquired && audioManager.requestAudioFocus(session.request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			session.acquired = true;
			return true;
		}
		return session.acquired;
	}

	public void release() {
		if (session != null) {
			session.release();
			session = null;
		}
	}

	public void transferTo(AudioFocus target) {
		if (target == this) return;
		target.release();
		target.session = session;
		session = null;
		if (target.session != null) target.session.callback = target.callback;
	}
}
