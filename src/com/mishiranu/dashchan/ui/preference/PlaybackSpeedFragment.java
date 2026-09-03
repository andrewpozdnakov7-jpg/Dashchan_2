package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;

public class PlaybackSpeedFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		CheckPreference speedControlPreference = addCheck(true, Preferences.KEY_VIDEO_PLAYBACK_SPEED_CONTROL,
				Preferences.DEFAULT_VIDEO_PLAYBACK_SPEED_CONTROL,
				R.string.enable_video_playback_speed_control,
				R.string.enable_video_playback_speed_control__summary);
		CheckPreference customSpeedPreference = addCheck(true, Preferences.KEY_VIDEO_CUSTOM_PLAYBACK_SPEED,
				Preferences.DEFAULT_VIDEO_CUSTOM_PLAYBACK_SPEED,
				R.string.custom_video_playback_speed,
				R.string.custom_video_playback_speed__summary);
		Preference<Void> customSpeedValuePreference = addButton(
				getString(R.string.custom_video_playback_speed_value), p ->
						PlaybackSpeedDialog.formatPlaybackSpeedSummary(requireContext(),
								Preferences.getVideoCustomPlaybackSpeedValue()));
		customSpeedValuePreference.setOnClickListener(p -> PlaybackSpeedDialog.show(getChildFragmentManager(),
				Preferences.getVideoCustomPlaybackSpeedValue(), playbackSpeed -> {
					Preferences.setVideoCustomPlaybackSpeedValue(playbackSpeed);
					if (Preferences.isRememberVideoPlaybackSpeed()
							&& Preferences.isPersistVideoPlaybackSpeed()) {
						Preferences.setSavedVideoPlaybackSpeed(playbackSpeed);
					}
					p.invalidate();
				}));
		addCheck(true, Preferences.KEY_REMEMBER_VIDEO_PLAYBACK_SPEED,
				Preferences.DEFAULT_REMEMBER_VIDEO_PLAYBACK_SPEED,
				R.string.remember_video_playback_speed,
				R.string.remember_video_playback_speed__summary);
		addCheck(true, Preferences.KEY_PERSIST_VIDEO_PLAYBACK_SPEED,
				Preferences.DEFAULT_PERSIST_VIDEO_PLAYBACK_SPEED,
				R.string.persist_video_playback_speed,
				R.string.persist_video_playback_speed__summary);

		addDependency(Preferences.KEY_VIDEO_CUSTOM_PLAYBACK_SPEED,
				Preferences.KEY_VIDEO_PLAYBACK_SPEED_CONTROL, true);
		addDependency(Preferences.KEY_REMEMBER_VIDEO_PLAYBACK_SPEED,
				Preferences.KEY_VIDEO_PLAYBACK_SPEED_CONTROL, true);
		addDependency(Preferences.KEY_PERSIST_VIDEO_PLAYBACK_SPEED,
				Preferences.KEY_REMEMBER_VIDEO_PLAYBACK_SPEED, true);
		Runnable updateCustomSpeedValueState = () -> customSpeedValuePreference
				.setEnabled(speedControlPreference.getValue() && customSpeedPreference.getValue());
		speedControlPreference.setOnAfterChangeListener(p -> updateCustomSpeedValueState.run());
		customSpeedPreference.setOnAfterChangeListener(p -> updateCustomSpeedValueState.run());
		updateCustomSpeedValueState.run();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.playback_speed), null);
	}
}
