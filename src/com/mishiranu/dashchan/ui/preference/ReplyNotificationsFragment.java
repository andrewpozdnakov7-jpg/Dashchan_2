package com.mishiranu.dashchan.ui.preference;

import android.Manifest;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.push.ReplyPushContract;
import com.mishiranu.dashchan.content.push.ReplyPushManager;
import com.mishiranu.dashchan.content.service.BackgroundWatcherWorker;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.util.Calendar;
import java.util.Objects;

public class ReplyNotificationsFragment extends PreferenceFragment {
	private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> {});

	private String lastInstallationId;
	private boolean lastResetPending;

	private final Runnable pushStateRunnable = new Runnable() {
		@Override
		public void run() {
			View view = getView();
			if (view == null) {
				return;
			}
			String installationId = ReplyPushManager.getInstallationId(requireContext());
			boolean pending = ReplyPushManager.isIdentityResetPending(requireContext());
			if (!Objects.equals(lastInstallationId, installationId) || lastResetPending != pending) {
				refreshPreferences();
			} else if (pending) {
				view.postDelayed(this, 1000L);
			}
		}
	};

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshPreferences();
	}

	@Override
	public void onPause() {
		View view = getView();
		if (view != null) {
			view.removeCallbacks(pushStateRunnable);
		}
		super.onPause();
	}

	@Override
	public void onDestroyView() {
		View view = getView();
		if (view != null) {
			view.removeCallbacks(pushStateRunnable);
		}
		super.onDestroyView();
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.replies_and_notifications), null);
	}

	private void refreshPreferences() {
		View view = getView();
		if (view == null) {
			return;
		}
		view.removeCallbacks(pushStateRunnable);
		removeAllPreferences();

		CheckPreference trackingPreference = addCheck(true, Preferences.KEY_TRACK_MY_POSTS,
				Preferences.DEFAULT_TRACK_MY_POSTS, R.string.track_replies, R.string.track_replies__summary);
		trackingPreference.setOnAfterChangeListener(p -> {
			if (!p.getValue() && Preferences.isReplyPushEnabled()) {
				Preferences.setReplyPushEnabled(false);
				ReplyPushManager.disable(requireContext());
			}
			if (p.getValue() && Preferences.isTrackedRepliesNotificationsEnabled()) {
				requestNotificationPermission();
			}
			BackgroundWatcherWorker.updateSchedule(requireContext());
			refreshPreferences();
		});

		if (trackingPreference.getValue()) {
			addHeader(R.string.reply_check_methods);
			CheckPreference localPreference = addCheck(true, Preferences.KEY_TRACKED_REPLIES_LOCAL_CHECK,
					Preferences.DEFAULT_TRACKED_REPLIES_LOCAL_CHECK, R.string.tracked_replies_local_check,
					R.string.tracked_replies_local_check__summary);
			localPreference.setOnAfterChangeListener(p -> {
				if (p.getValue() && Preferences.isTrackedRepliesNotificationsEnabled()) {
					requestNotificationPermission();
				}
				BackgroundWatcherWorker.updateSchedule(requireContext());
				refreshPreferences();
			});
			if (localPreference.getValue()) {
				Preference<String> refreshIntervalPreference = addEdit(
						Preferences.KEY_TRACKED_REPLIES_REFRESH_INTERVAL,
						Preferences.DEFAULT_TRACKED_REPLIES_REFRESH_INTERVAL,
						R.string.tracked_replies_refresh_interval,
						p -> getString(R.string.every_number_min__format,
								Preferences.getTrackedRepliesRefreshIntervalMinutes()),
						null, InputType.TYPE_CLASS_NUMBER);
				refreshIntervalPreference.setOnAfterChangeListener(p -> {
					String normalized = Integer.toString(Preferences.getTrackedRepliesRefreshIntervalMinutes());
					if (!normalized.equals(p.getValue())) {
						p.setValue(normalized);
					}
				});
			}

			if (ReplyPushManager.isSupported()) {
				addReplyPushPreference();
			}

			boolean anyMethod = localPreference.getValue()
					|| ReplyPushManager.isSupported() && Preferences.isReplyPushEnabled();
			if (anyMethod) {
				addNotificationPreferences();
			}
		}

		lastInstallationId = ReplyPushManager.getInstallationId(requireContext());
		lastResetPending = ReplyPushManager.isIdentityResetPending(requireContext());
		if (lastResetPending) {
			view.postDelayed(pushStateRunnable, 1000L);
		}
	}

	private void addReplyPushPreference() {
		CharSequence summary = Preferences.isReplyPushEnabled() && !ReplyPushManager.isConfigured()
				? getString(R.string.reply_push_waiting_for_configuration)
				: getString(R.string.reply_push__summary);
		CheckPreference preference = addCheck(true, Preferences.KEY_REPLY_PUSH_ENABLED,
				Preferences.DEFAULT_REPLY_PUSH_ENABLED, getString(R.string.reply_push), summary);
		preference.setOnBeforeChangeListener((p, value) -> {
			if (!value) {
				return true;
			}
			new AlertDialog.Builder(requireContext())
					.setTitle(R.string.reply_push)
					.setMessage(R.string.reply_push_consent__message)
					.setPositiveButton(R.string.enable, (dialog, which) -> {
						Preferences.setReplyPushEnabled(true);
						ReplyPushManager.enable(requireContext());
						requestNotificationPermission();
						if (!ReplyPushManager.isConfigured()) {
							ClickableToast.show(R.string.reply_push_waiting_for_configuration);
						}
						refreshPreferences();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return false;
		});
		preference.setOnAfterChangeListener(p -> {
			if (!p.getValue()) {
				ReplyPushManager.disable(requireContext());
			}
			refreshPreferences();
		});
		if (Preferences.isReplyPushEnabled()) {
			addReplyPushIdentityPreferences();
		}
	}

	private void addReplyPushIdentityPreferences() {
		addHeader(R.string.reply_push_installation);
		String installationId = ReplyPushManager.getInstallationId(requireContext());
		if (!StringUtils.isEmpty(installationId)) {
			addButton(getString(R.string.reply_push_installation_id), installationId).setSelectable(false);
			addButton(R.string.reply_push_copy_installation_id, 0).setOnClickListener(p -> {
				StringUtils.copyToClipboard(requireContext(), installationId);
				ClickableToast.show(R.string.reply_push_installation_id_copied);
			});
		}
		addReplyPushIdentityResetPreference();
	}

	private void addNotificationPreferences() {
		addHeader(R.string.reply_notification_behavior);
		CheckPreference notificationsPreference = addCheck(true,
				Preferences.KEY_TRACKED_REPLIES_NOTIFICATIONS,
				Preferences.DEFAULT_TRACKED_REPLIES_NOTIFICATIONS,
				R.string.tracked_replies_notifications,
				R.string.tracked_replies_notifications__summary);
		notificationsPreference.setOnAfterChangeListener(p -> {
			if (p.getValue()) {
				requestNotificationPermission();
			}
			refreshPreferences();
		});
		if (notificationsPreference.getValue()) {
			CheckPreference quietHoursPreference = addCheck(true,
					Preferences.KEY_REPLY_PUSH_QUIET_HOURS_ENABLED,
					Preferences.DEFAULT_REPLY_PUSH_QUIET_HOURS_ENABLED,
					R.string.reply_push_quiet_hours, R.string.reply_push_quiet_hours__summary);
			quietHoursPreference.setOnAfterChangeListener(p -> refreshPreferences());
			if (quietHoursPreference.getValue()) {
				addButton(getString(R.string.reply_push_quiet_hours_start),
						formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursStart()))
						.setOnClickListener(p -> showReplyPushQuietTimePicker(true));
				addButton(getString(R.string.reply_push_quiet_hours_end),
						formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursEnd()))
						.setOnClickListener(p -> showReplyPushQuietTimePicker(false));
			}
		}
	}

	private void addReplyPushIdentityResetPreference() {
		boolean configured = ReplyPushManager.isConfigured();
		boolean pending = ReplyPushManager.isIdentityResetPending(requireContext());
		boolean failed = ReplyPushManager.didIdentityResetFail(requireContext());
		long cooldownRemaining = ReplyPushManager.getIdentityResetCooldownRemaining(requireContext());
		CharSequence summary;
		if (pending) {
			summary = getString(R.string.reply_push_reset_pending);
		} else if (cooldownRemaining > 0L) {
			summary = formatReplyPushResetCooldown(cooldownRemaining);
		} else if (failed) {
			summary = getString(R.string.reply_push_reset_failed);
		} else if (!configured) {
			summary = getString(R.string.reply_push_waiting_for_configuration);
		} else {
			summary = getString(R.string.reply_push_reset_identity__summary);
		}
		Preference<Void> resetPreference = addButton(getString(R.string.reply_push_reset_identity), summary);
		resetPreference.setEnabled(configured && !pending && cooldownRemaining == 0L);
		resetPreference.setOnClickListener(p -> {
			String message = getString(R.string.reply_push_reset_identity__message);
			if (ReplyPushContract.IDENTITY_RESET_COOLDOWN_MILLIS > 0L) {
				message += "\n\n" + getString(R.string.reply_push_reset_identity__cooldown);
			}
			new AlertDialog.Builder(requireContext())
					.setTitle(R.string.reply_push_reset_identity)
					.setMessage(message)
					.setPositiveButton(R.string.reply_push_reset_identity, (dialog, which) -> {
						if (ReplyPushManager.resetIdentity(requireContext())) {
							ClickableToast.show(R.string.reply_push_reset_started);
						} else {
							ClickableToast.show(R.string.reply_push_reset_unavailable);
						}
						refreshPreferences();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		});
	}

	private CharSequence formatReplyPushResetCooldown(long remainingMillis) {
		long minutes = Math.max(1L, (remainingMillis + 60_000L - 1L) / 60_000L);
		if (minutes >= 60L) {
			return getString(R.string.reply_push_reset_available_hours__format,
					minutes / 60L, minutes % 60L);
		}
		return getString(R.string.reply_push_reset_available_minutes__format, minutes);
	}

	private CharSequence formatReplyPushQuietTime(int minutes) {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
		calendar.set(Calendar.MINUTE, minutes % 60);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return DateFormat.getTimeFormat(requireContext()).format(calendar.getTime());
	}

	private void showReplyPushQuietTimePicker(boolean start) {
		int minutes = start ? Preferences.getReplyPushQuietHoursStart()
				: Preferences.getReplyPushQuietHoursEnd();
		new TimePickerDialog(requireContext(), (timePicker, hourOfDay, minute) -> {
			int value = hourOfDay * 60 + minute;
			if (start) {
				Preferences.setReplyPushQuietHoursStart(value);
			} else {
				Preferences.setReplyPushQuietHoursEnd(value);
			}
			refreshPreferences();
		}, minutes / 60, minutes % 60, DateFormat.is24HourFormat(requireContext())).show();
	}

	private void requestNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && requireContext()
				.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
		}
	}
}
