package com.mishiranu.dashchan.ui.preference;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.SeekPreference;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.SharedPreferences;

public class VideoPlayerSettingsFragment extends PreferenceFragment {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		if (BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION) {
			addButton(R.string.player_buttons_layout, R.string.player_buttons_layout_summary).setOnClickListener(p ->
					((FragmentHandler) requireActivity()).pushFragment(new PlayerButtonsFragment()));
		}

		addCheck(true, Preferences.KEY_HARDWARE_VIDEO_ACCELERATION,
				Preferences.DEFAULT_HARDWARE_VIDEO_ACCELERATION,
				R.string.hardware_video_acceleration, R.string.hardware_video_acceleration__summary);
		Pair<Boolean, String> playerLoadResult = VideoPlayer.loadLibraries(requireContext());
		if (!playerLoadResult.first) {
			if (playerLoadResult.second != null) {
				SpannableStringBuilder builder = new SpannableStringBuilder(playerLoadResult.second);
				if (builder.length() == 0) {
					builder.append(getString(R.string.unknown_error));
				}
				builder.setSpan(new ForegroundColorSpan(ResourceUtils.getColor(requireContext(),
						R.attr.colorTextError)), 0, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
				addButton(null, builder).setSelectable(false);
			} else {
				addButton(0, R.string.requires_decoding_libraries__sentence).setSelectable(false);
			}
		}
		CheckPreference videoPlayerPreference = addCheck(true, Preferences.KEY_USE_VIDEO_PLAYER,
				Preferences.DEFAULT_USE_VIDEO_PLAYER, R.string.use_built_in_video_player,
				R.string.use_built_in_video_player__summary);
		videoPlayerPreference.setEnabled(playerLoadResult.first);
		CheckPreference rememberPositionPreference = addCheck(true, Preferences.KEY_REMEMBER_LAST_VIDEO_POSITION,
				Preferences.DEFAULT_REMEMBER_LAST_VIDEO_POSITION, R.string.remember_last_video_position,
				R.string.remember_last_video_position__summary);
		rememberPositionPreference.setEnabled(playerLoadResult.first);
		rememberPositionPreference.setOnAfterChangeListener(p -> {
			if (!rememberPositionPreference.getValue()) Preferences.setSavedLastVideoPosition(null);
		});
		addCheck(true, Preferences.KEY_VIDEO_START_MUTED, Preferences.DEFAULT_VIDEO_START_MUTED,
				R.string.start_videos_muted, R.string.start_videos_muted__summary)
				.setEnabled(playerLoadResult.first);
		CheckPreference audioBoostPreference = addCheck(true, Preferences.KEY_VIDEO_AUDIO_BOOST,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST, R.string.video_audio_boost,
				R.string.video_audio_boost__summary);
		SeekPreference audioBoostLevelPreference = addSeek(Preferences.KEY_VIDEO_AUDIO_BOOST_DB,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST_DB, R.string.video_audio_boost_level,
				R.string.video_audio_boost_level__format, null, Preferences.MIN_VIDEO_AUDIO_BOOST_DB,
				Preferences.MAX_VIDEO_AUDIO_BOOST_DB, 3);
		addList(Preferences.KEY_VIDEO_COMPLETION, enumList(Preferences.VideoCompletionMode.values(), o -> o.value),
				Preferences.DEFAULT_VIDEO_COMPLETION.value, R.string.action_on_playback_completion,
				enumResList(Preferences.VideoCompletionMode.values(), o -> o.titleResId))
				.setEnabled(playerLoadResult.first);
		addCheck(true, Preferences.KEY_VIDEO_PLAY_AFTER_SCROLL, Preferences.DEFAULT_VIDEO_PLAY_AFTER_SCROLL,
				R.string.play_after_scroll, R.string.play_after_scroll__summary).setEnabled(playerLoadResult.first);
		addCheck(true, Preferences.KEY_VIDEO_SEEK_ANY_FRAME, Preferences.DEFAULT_VIDEO_SEEK_ANY_FRAME,
				R.string.seek_any_frame, R.string.seek_any_frame__summary).setEnabled(playerLoadResult.first);
		addCheck(true, Preferences.KEY_VIDEO_ZOOM_GESTURES, Preferences.DEFAULT_VIDEO_ZOOM_GESTURES,
				R.string.video_zoom_gestures, R.string.video_zoom_gestures__summary).setEnabled(playerLoadResult.first);
		addList(Preferences.KEY_YOUTUBE_OPEN_MODE,
				enumList(Preferences.YouTubeOpenMode.values(), value -> value.value),
				Preferences.DEFAULT_YOUTUBE_OPEN_MODE.value, R.string.youtube_open_mode,
				enumResList(Preferences.YouTubeOpenMode.values(), value -> value.titleResId));
		addCheck(true, Preferences.KEY_VIDEO_PICTURE_IN_PICTURE,
				Preferences.DEFAULT_VIDEO_PICTURE_IN_PICTURE,
				R.string.video_picture_in_picture,
				R.string.video_picture_in_picture__summary).setEnabled(playerLoadResult.first);
		addCheck(true, Preferences.KEY_VIDEO_PICTURE_IN_PICTURE_AUTO,
				Preferences.DEFAULT_VIDEO_PICTURE_IN_PICTURE_AUTO,
				R.string.video_picture_in_picture_auto,
				R.string.video_picture_in_picture_auto__summary).setEnabled(playerLoadResult.first);
		addList(Preferences.KEY_VIDEO_SCREEN_OFF_ACTION,
				enumList(Preferences.VideoScreenOffAction.values(), value -> value.value),
				Preferences.DEFAULT_VIDEO_SCREEN_OFF_ACTION.value, R.string.video_screen_off_action,
				enumResList(Preferences.VideoScreenOffAction.values(), value -> value.titleResId),
				enumResList(Preferences.VideoScreenOffAction.values(), value -> value.summaryResId))
				.setEnabled(playerLoadResult.first);
		Preference<Void> playbackSpeedPreference = addButton(R.string.playback_speed,
				R.string.playback_speed_settings__summary);
		playbackSpeedPreference.setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(new PlaybackSpeedFragment()));
		addCheck(true, Preferences.KEY_ATTACHMENT_VIDEO_PREVIEW,
				Preferences.DEFAULT_ATTACHMENT_VIDEO_PREVIEW,
				R.string.attachment_video_preview,
				R.string.attachment_video_preview__summary).setEnabled(playerLoadResult.first);
		if (playerLoadResult.first) {
			addDependency(Preferences.KEY_REMEMBER_LAST_VIDEO_POSITION, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_START_MUTED, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_COMPLETION, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_PLAY_AFTER_SCROLL, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_SEEK_ANY_FRAME, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_PICTURE_IN_PICTURE, Preferences.KEY_USE_VIDEO_PLAYER, true);
			addDependency(Preferences.KEY_VIDEO_PICTURE_IN_PICTURE_AUTO,
					Preferences.KEY_VIDEO_PICTURE_IN_PICTURE, true);
			addDependency(Preferences.KEY_VIDEO_SCREEN_OFF_ACTION,
					Preferences.KEY_VIDEO_PICTURE_IN_PICTURE, true);
			addDependency(Preferences.KEY_ATTACHMENT_VIDEO_PREVIEW, Preferences.KEY_USE_VIDEO_PLAYER, true);
		}
		Runnable updatePlayerState = () -> {
			boolean playerEnabled = playerLoadResult.first && videoPlayerPreference.getValue();
			audioBoostPreference.setEnabled(playerEnabled);
			audioBoostLevelPreference.setEnabled(playerEnabled && audioBoostPreference.getValue());
			playbackSpeedPreference.setEnabled(playerEnabled);
		};
		videoPlayerPreference.setOnAfterChangeListener(p -> updatePlayerState.run());
		audioBoostPreference.setOnAfterChangeListener(p -> updatePlayerState.run());
		updatePlayerState.run();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.video_player), null);
	}
}
