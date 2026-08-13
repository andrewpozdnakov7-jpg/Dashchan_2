package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.translation.GeminiNanoTranslationBridge;
import com.mishiranu.dashchan.content.translation.GoogleTranslationBridge;
import com.mishiranu.dashchan.content.translation.TranslationController;
import com.mishiranu.dashchan.content.translation.TranslationEngine;
import com.mishiranu.dashchan.content.translation.TranslationModel;
import com.mishiranu.dashchan.content.translation.TranslationModelManager;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.ui.preference.core.SeekPreference;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class ExperimentalFragment extends PreferenceFragment implements TranslationModelManager.Listener,
		GoogleTranslationBridge.Listener, GeminiNanoTranslationBridge.Listener {
	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	private void refreshPreferences() {
		if (getView() == null) {
			return;
		}
		removeAllPreferences();
		addButton(R.string.whats_new_preview, R.string.whats_new_preview__summary)
				.setOnClickListener(p -> WhatsNewDialog.show(getChildFragmentManager()));
		CheckPreference hardwareAccelerationPreference = addCheck(true,
				Preferences.KEY_HARDWARE_VIDEO_ACCELERATION,
				Preferences.DEFAULT_HARDWARE_VIDEO_ACCELERATION,
				R.string.hardware_video_acceleration, R.string.hardware_video_acceleration__summary);
		hardwareAccelerationPreference.setOnAfterChangeListener(p -> {
			if (!p.getValue() && VideoDiagnostics.isRecording()) {
				VideoDiagnostics.stop();
			}
			refreshPreferences();
		});
		if (hardwareAccelerationPreference.getValue()) {
			addVideoDiagnosticsPreferences();
		}
		addCheck(true, Preferences.KEY_IMAGE_EDITOR, Preferences.DEFAULT_IMAGE_EDITOR,
				R.string.image_editor, R.string.image_editor__summary);
		addCheck(true, Preferences.KEY_OPEN_CONFIGURED_ATTACHMENT_FOLDER,
				Preferences.DEFAULT_OPEN_CONFIGURED_ATTACHMENT_FOLDER,
				R.string.open_configured_attachment_folder,
				R.string.open_configured_attachment_folder__summary);
		CheckPreference combinedFeedsPreference = addCheck(true, Preferences.KEY_COMBINED_FEEDS_ENABLED,
				Preferences.DEFAULT_COMBINED_FEEDS_ENABLED, R.string.combined_feeds,
				R.string.combined_feeds__summary);
		combinedFeedsPreference.setOnAfterChangeListener(p -> refreshPreferences());
		if (combinedFeedsPreference.getValue()) {
			addButton(R.string.configure_combined_feeds, R.string.configure_combined_feeds__summary)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new CombinedFeedsFragment()));
		}
		CheckPreference wallpaperPreference = addCheck(true, Preferences.KEY_WALLPAPER_ENABLED,
				Preferences.DEFAULT_WALLPAPER_ENABLED, R.string.wallpaper_background,
				R.string.wallpaper_background__summary);
		wallpaperPreference.setOnAfterChangeListener(p -> {
			refreshPreferences();
			requireActivity().recreate();
		});
		if (wallpaperPreference.getValue()) {
			addButton(R.string.configure_wallpaper, R.string.configure_wallpaper__summary)
					.setOnClickListener(p -> ((FragmentHandler) requireActivity())
							.pushFragment(new WallpaperFragment()));
		}
		if (BuildConfig.ENABLE_LOCAL_TRANSLATION) {
			addTranslationPreferences();
		}
		CheckPreference audioBoostPreference = addCheck(true, Preferences.KEY_VIDEO_AUDIO_BOOST,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST, R.string.video_audio_boost,
				R.string.video_audio_boost__summary);
		SeekPreference audioBoostLevelPreference = addSeek(Preferences.KEY_VIDEO_AUDIO_BOOST_DB,
				Preferences.DEFAULT_VIDEO_AUDIO_BOOST_DB, R.string.video_audio_boost_level,
				R.string.video_audio_boost_level__format, null, Preferences.MIN_VIDEO_AUDIO_BOOST_DB,
				Preferences.MAX_VIDEO_AUDIO_BOOST_DB, 3);
		audioBoostLevelPreference.setEnabled(audioBoostPreference.getValue());
		audioBoostPreference.setOnAfterChangeListener(p -> refreshPreferences());
		addHeader(R.string.additional);
		if (BuildConfig.ALLOW_GMS_SECURITY_PROVIDER) {
			addCheck(true, Preferences.KEY_USE_GMS_PROVIDER, Preferences.DEFAULT_USE_GMS_PROVIDER,
					R.string.use_gms_security_provider, R.string.use_gms_security_provider__summary);
		}
	}

	private void addTranslationPreferences() {
		CheckPreference translationPreference = addCheck(true, Preferences.KEY_LOCAL_TRANSLATION,
				Preferences.DEFAULT_LOCAL_TRANSLATION, R.string.local_translation,
				R.string.local_translation__summary);
		translationPreference.setOnAfterChangeListener(p -> refreshPreferences());
		if (!translationPreference.getValue()) {
			return;
		}
		addList(Preferences.KEY_TRANSLATION_NATIVE_LANGUAGE, Arrays.asList("ru", "en"),
				Preferences.DEFAULT_TRANSLATION_NATIVE_LANGUAGE, R.string.translation_native_language,
				Arrays.asList(getText(R.string.translation_language_russian),
						getText(R.string.translation_language_english)))
				.setOnAfterChangeListener(p -> {
					TranslationController.getInstance().unload();
					refreshPreferences();
				});
		if (BuildConfig.ENABLE_GOOGLE_TRANSLATION || BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION) {
			ArrayList<String> engineValues = new ArrayList<>();
			ArrayList<CharSequence> engineTitles = new ArrayList<>();
			engineValues.add(TranslationEngine.MOZILLA.value);
			engineTitles.add(getText(R.string.translation_engine_mozilla));
			if (BuildConfig.ENABLE_GOOGLE_TRANSLATION) {
				engineValues.add(TranslationEngine.GOOGLE.value);
				engineTitles.add(getText(R.string.translation_engine_google));
			}
			if (BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION) {
				engineValues.add(TranslationEngine.GEMINI_NANO.value);
				engineTitles.add(getText(R.string.translation_engine_gemini_nano));
			}
			addList(Preferences.KEY_TRANSLATION_ENGINE, engineValues, Preferences.DEFAULT_TRANSLATION_ENGINE,
					R.string.translation_engine, engineTitles)
					.setOnAfterChangeListener(p -> {
						TranslationController.getInstance().unload();
						refreshPreferences();
					});
		}
		addCheck(true, Preferences.KEY_TRANSLATION_AUTO, Preferences.DEFAULT_TRANSLATION_AUTO,
				R.string.translation_automatic, R.string.translation_automatic__summary);

		TranslationModel.Direction direction = TranslationModel.forNativeLanguage(
				Preferences.getTranslationNativeLanguage());
		TranslationEngine engine = Preferences.getTranslationEngine();
		String directionName = direction.getDisplayName(requireContext());
		TranslationModelManager.State state;
		int progress;
		long downloadedBytes;
		String error;
		long packageSize;
		GeminiNanoTranslationBridge.Snapshot geminiSnapshot = null;
		if (engine == TranslationEngine.GOOGLE) {
			GoogleTranslationBridge.Snapshot snapshot = GoogleTranslationBridge.getSnapshot(direction);
			state = snapshot.state;
			progress = snapshot.progress;
			downloadedBytes = snapshot.downloadedBytes;
			error = snapshot.error;
			packageSize = GoogleTranslationBridge.APPROXIMATE_MODEL_SIZE;
		} else if (engine == TranslationEngine.GEMINI_NANO) {
			geminiSnapshot = GeminiNanoTranslationBridge.getSnapshot(direction);
			state = geminiSnapshot.state;
			progress = geminiSnapshot.progress;
			downloadedBytes = geminiSnapshot.downloadedBytes;
			error = geminiSnapshot.error;
			packageSize = geminiSnapshot.totalBytes;
		} else {
			TranslationModelManager.Snapshot snapshot = TranslationModelManager.getInstance().getSnapshot(direction);
			state = snapshot.state;
			progress = snapshot.progress;
			downloadedBytes = 0L;
			error = snapshot.error;
			packageSize = direction.compressedSize;
		}
		String summary;
		switch (state) {
			case INSTALLED: {
				summary = engine == TranslationEngine.GEMINI_NANO
						? getString(R.string.translation_package_gemini_installed)
						: getString(R.string.translation_package_installed__format, directionName,
								formatSize(engine == TranslationEngine.GOOGLE
										? GoogleTranslationBridge.APPROXIMATE_MODEL_SIZE : direction.uncompressedSize));
				break;
			}
			case CHECKING: {
				summary = getString(R.string.translation_package_checking);
				break;
			}
			case DOWNLOADING: {
				if (engine == TranslationEngine.GEMINI_NANO) {
					summary = packageSize > 0L
							? getString(R.string.translation_package_downloading_bytes_total__format,
									formatSize(downloadedBytes), formatSize(packageSize))
							: getString(R.string.translation_package_downloading_bytes__format,
									formatSize(downloadedBytes));
				} else {
					summary = engine == TranslationEngine.GOOGLE
							? getString(R.string.translation_package_downloading_bytes__format,
									formatSize(downloadedBytes))
							: getString(R.string.translation_package_downloading__format, progress);
				}
				break;
			}
			case ERROR: {
				summary = engine == TranslationEngine.GEMINI_NANO && geminiSnapshot != null &&
						!geminiSnapshot.supported
						? getString(R.string.translation_package_gemini_unavailable)
						: getString(R.string.translation_package_error__format, error);
				break;
			}
			default: {
				if (engine == TranslationEngine.GEMINI_NANO && geminiSnapshot != null) {
					summary = geminiSnapshot.supported
							? getString(R.string.translation_package_gemini_downloadable)
							: getString(R.string.translation_package_gemini_unavailable);
				} else {
					summary = getString(R.string.translation_package_not_installed__format, directionName,
							formatSize(packageSize));
				}
				break;
			}
		}
		Preference<Void> packagePreference = addButton(getString(R.string.translation_language_package), summary);
		packagePreference.setSelectable(state != TranslationModelManager.State.DOWNLOADING &&
				state != TranslationModelManager.State.CHECKING &&
				!(engine == TranslationEngine.GEMINI_NANO && state == TranslationModelManager.State.INSTALLED));
		GeminiNanoTranslationBridge.Snapshot finalGeminiSnapshot = geminiSnapshot;
		packagePreference.setOnClickListener(p -> {
			if (engine == TranslationEngine.GEMINI_NANO && finalGeminiSnapshot != null &&
					!finalGeminiSnapshot.supported) {
				GeminiNanoTranslationBridge.refresh();
				return;
			}
			if (state == TranslationModelManager.State.INSTALLED) {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.translation_package_delete)
						.setMessage(R.string.translation_package_delete__message)
						.setPositiveButton(R.string.delete, (dialog, which) -> {
							if (engine == TranslationEngine.GOOGLE) {
								GoogleTranslationBridge.delete(direction, () -> {
									ClickableToast.show(R.string.translation_package_deleted);
									refreshPreferences();
								});
							} else if (TranslationModelManager.getInstance().delete(direction)) {
								ClickableToast.show(R.string.translation_package_deleted);
								refreshPreferences();
							}
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			} else {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.translation_package_download)
						.setMessage(engine == TranslationEngine.GOOGLE
								? R.string.translation_package_download_google__message
								: engine == TranslationEngine.GEMINI_NANO
										? R.string.translation_package_download_gemini__message
										: R.string.translation_package_download__message)
						.setPositiveButton(R.string.translation_package_download_action,
								(dialog, which) -> {
									if (engine == TranslationEngine.GOOGLE) {
										GoogleTranslationBridge.download(direction);
									} else if (engine == TranslationEngine.GEMINI_NANO) {
										GeminiNanoTranslationBridge.download(direction);
									} else {
										TranslationModelManager.getInstance().download(direction);
									}
								})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}
		});
	}

	private static String formatSize(long bytes) {
		return String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
	}

	private void addVideoDiagnosticsPreferences() {
		boolean recording = VideoDiagnostics.isRecording();
		Preference<Void> capturePreference = addButton(recording
						? getString(R.string.video_diagnostics_stop)
						: getString(R.string.video_diagnostics_start),
				recording ? getString(R.string.video_diagnostics_stop__summary)
						: getString(R.string.video_diagnostics_start__summary));
		capturePreference.setOnClickListener(p -> {
			if (VideoDiagnostics.isRecording()) {
				File file = VideoDiagnostics.stop();
				refreshPreferences();
				if (file != null) {
					new AlertDialog.Builder(requireContext())
							.setTitle(R.string.video_diagnostics_saved)
							.setMessage(R.string.video_diagnostics_saved__message)
							.setPositiveButton(R.string.share, (dialog, which) ->
									NavigationUtils.shareFile(requireContext(), file, file.getName()))
							.setNegativeButton(android.R.string.ok, null)
							.show();
				} else {
					ClickableToast.show(R.string.video_diagnostics_save_failed);
				}
			} else {
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.video_diagnostics_start)
						.setMessage(R.string.video_diagnostics_privacy_notice)
						.setPositiveButton(R.string.video_diagnostics_start, (dialog, which) -> {
							VideoDiagnostics.start();
							refreshPreferences();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}
		});
		if (!recording) {
			File lastFile = VideoDiagnostics.getLastFile();
			if (lastFile != null) {
				addButton(R.string.video_diagnostics_share, R.string.video_diagnostics_share__summary)
						.setOnClickListener(p ->
								NavigationUtils.shareFile(requireContext(), lastFile, lastFile.getName()));
				addButton(R.string.video_diagnostics_delete, 0).setOnClickListener(p -> {
					if (VideoDiagnostics.deleteLastFile()) {
						ClickableToast.show(R.string.video_diagnostics_deleted);
						refreshPreferences();
					} else {
						ClickableToast.show(R.string.unknown_error);
					}
				});
			}
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.experimental_features), null);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (BuildConfig.ENABLE_LOCAL_TRANSLATION) {
			TranslationModelManager.getInstance().register(this);
			if (BuildConfig.ENABLE_GOOGLE_TRANSLATION) {
				GoogleTranslationBridge.register(this);
			}
			if (BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION) {
				GeminiNanoTranslationBridge.register(this);
			}
		}
		refreshPreferences();
	}

	@Override
	public void onPause() {
		if (BuildConfig.ENABLE_LOCAL_TRANSLATION) {
			TranslationModelManager.getInstance().unregister(this);
			if (BuildConfig.ENABLE_GOOGLE_TRANSLATION) {
				GoogleTranslationBridge.unregister(this);
			}
			if (BuildConfig.ENABLE_GEMINI_NANO_TRANSLATION) {
				GeminiNanoTranslationBridge.unregister(this);
			}
		}
		super.onPause();
	}

	@Override
	public void onTranslationModelChanged(TranslationModel.Direction direction,
			TranslationModelManager.Snapshot snapshot) {
		refreshPreferences();
	}

	@Override
	public void onGoogleTranslationModelChanged(GoogleTranslationBridge.Snapshot snapshot) {
		refreshPreferences();
	}

	@Override
	public void onGeminiNanoTranslationModelChanged(GeminiNanoTranslationBridge.Snapshot snapshot) {
		refreshPreferences();
	}

}
