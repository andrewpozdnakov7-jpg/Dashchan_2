package com.mishiranu.dashchan.ui.preference;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.ReplyNotificationTargets;
import com.mishiranu.dashchan.content.WatcherNotifications;
import com.mishiranu.dashchan.content.push.ReplyPushManager;
import com.mishiranu.dashchan.content.service.BackgroundWatcherWorker;
import com.mishiranu.dashchan.content.storage.MyPostsStorage;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import java.util.Calendar;

public class ReplyNotificationsFragment extends PreferenceFragment {
	enum Mode {
		MANUAL(false, false, R.string.reply_method_manual), LOCAL(true, false, R.string.reply_method_local),
		SERVER(false, true, R.string.reply_method_server), BOTH(true, true, R.string.reply_method_both);
		final boolean local, server;
		final int title;

		Mode(boolean local, boolean server, int title) {
			this.local = local;
			this.server = server;
			this.title = title;
		}

		static Mode from(boolean local, boolean server) {
			return server ? (local ? BOTH : SERVER) : (local ? LOCAL : MANUAL);
		}

		static Mode current() {
			return from(Preferences.isTrackedRepliesLocalCheckEnabled(),
					ReplyPushManager.isSupported() && Preferences.isReplyPushEnabled());
		}
	}

	private static final String SUBPAGE = "replySubpage";
	static final String ADVANCED = "advanced", QUIET = "quiet", REGISTRATION = "registration";
	private final ActivityResultLauncher<String> permission = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> refreshPreferences());
	private final Runnable refresh = this::refreshPreferences;
	private final SharedPreferences.Listener pendingListener = key -> {
		View view = getView();
		if (Preferences.KEY_REPLY_HISTORY_DELETE_PENDING.equals(key) && view != null) view.post(refresh);
	};

	static ReplyNotificationsFragment subpage(String page) {
		ReplyNotificationsFragment result = new ReplyNotificationsFragment();
		Bundle args = new Bundle();
		args.putString(SUBPAGE, page);
		result.setArguments(args);
		return result;
	}

	private String subpage() {
		return getArguments() != null ? getArguments().getString(SUBPAGE) : null;
	}

	@Override protected boolean groupSettingsRows() { return true; }

	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }

	@Override public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		refreshPreferences();
	}

	@Override public void onResume() {
		super.onResume();
		getPreferences().register(pendingListener);
		refreshPreferences();
	}

	@Override public void onPause() {
		getPreferences().unregister(pendingListener);
		if (getView() != null) getView().removeCallbacks(refresh);
		super.onPause();
	}

	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		int title = QUIET.equals(subpage()) ? R.string.reply_push_quiet_hours
				: REGISTRATION.equals(subpage()) ? R.string.reply_push_installation
				: ADVANCED.equals(subpage()) ? R.string.reply_advanced : R.string.replies_and_notifications;
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(title), null);
	}

	private void refreshPreferences() {
		View view = getView();
		if (view == null) return;
		view.removeCallbacks(refresh);
		removeAllPreferences();
		if (QUIET.equals(subpage())) {
			if (Preferences.isTrackMyPostsEnabled()) addQuietHours();
			return;
		}
		if (REGISTRATION.equals(subpage())) {
			if (Preferences.isTrackMyPostsEnabled() && Mode.current().server) addRegistration();
			return;
		}
		if (ADVANCED.equals(subpage())) {
			addAdvanced();
			return;
		}
		addHeader(R.string.reply_own_posts);
		addCheck(true, Preferences.KEY_SHOW_MY_POSTS, Preferences.DEFAULT_SHOW_MY_POSTS,
				R.string.highlight_my_posts, 0);
		CheckPreference history = addCheck(true, Preferences.KEY_TRACK_MY_POSTS, Preferences.DEFAULT_TRACK_MY_POSTS,
				R.string.track_replies, 0);
		history.setEnabled(!Preferences.isReplyHistoryDeletePending());
		history.setOnAfterChangeListener(p -> {
			if (!p.getValue()) stopTracking();
			BackgroundWatcherWorker.updateSchedule(requireContext());
			refreshPreferences();
		});
		if (history.getValue()) {
			addHeader(R.string.replies);
			Mode mode = Mode.current();
			String summary = getString(mode.title);
			if (mode.local) summary = getString(R.string.reply_method_with_interval, summary,
					Preferences.getTrackedRepliesRefreshIntervalMinutes());
			if (mode == Mode.MANUAL) summary += "\n" + getString(R.string.reply_manual_hint);
			addButton(getString(R.string.reply_method), summary).setOnClickListener(p -> Choice.show(this, Choice.METHOD, null));
			addNotificationSwitch();
			addButton(R.string.reply_advanced, R.string.reply_advanced_summary).setOnClickListener(p -> open(ADVANCED));
		} else {
			addHeader(R.string.reply_device_data);
			addErase();
		}
	}

	private void addErase() {
		addButton(R.string.reply_erase, Preferences.isReplyHistoryDeletePending() ? R.string.reply_erase_pending : 0)
				.setOnClickListener(p -> Choice.show(this, Choice.ERASE, null));
	}

	private void stopTracking() {
		if (Preferences.isReplyPushEnabled()) {
			Preferences.setReplyPushEnabled(false);
			ReplyPushManager.disable(requireContext());
		}
		WatcherNotifications.cancelAllReplies(requireContext());
	}

	private void eraseHistory() {
		MyPostsStorage storage = MyPostsStorage.getInstance();
		boolean push = Preferences.isReplyPushEnabled();
		if (!getPreferences().edit().put(Preferences.KEY_REPLY_HISTORY_DELETE_PENDING, true)
				.put(Preferences.KEY_TRACK_MY_POSTS, false).put(Preferences.KEY_REPLY_PUSH_ENABLED, false).commitChecked()) {
			ClickableToast.show(R.string.reply_history_delete_failed);
			return;
		}
		if (push) ReplyPushManager.disable(requireContext());
		WatcherNotifications.cancelAllReplies(requireContext());
		BackgroundWatcherWorker.updateSchedule(requireContext());
		storage.clearAllHistory(success -> {
			ClickableToast.show(success ? R.string.reply_history_deleted : R.string.reply_history_delete_failed);
			refreshPreferences();
		});
		refreshPreferences();
	}

	private void addAdvanced() {
		if (!Preferences.isTrackMyPostsEnabled()) {
			addErase();
			return;
		}
		Mode mode = Mode.current();
		if (mode.local || mode.server) addHeader(R.string.reply_check_methods);
		if (mode.local) {
			Preference<String> interval = addEdit(Preferences.KEY_TRACKED_REPLIES_REFRESH_INTERVAL,
					Preferences.DEFAULT_TRACKED_REPLIES_REFRESH_INTERVAL, R.string.tracked_replies_refresh_interval,
					p -> getString(R.string.reply_interval_summary, Preferences.getTrackedRepliesRefreshIntervalMinutes()),
					null, InputType.TYPE_CLASS_NUMBER);
			interval.setOnAfterChangeListener(p -> {
				String value = Integer.toString(Preferences.getTrackedRepliesRefreshIntervalMinutes());
				if (!value.equals(p.getValue())) p.setValue(value);
			});
		}
		if (mode.server) {
			addButton(R.string.reply_push_installation, ReplyPushManager.isConfigured()
					? R.string.reply_registration_summary : R.string.reply_push_waiting_for_configuration)
					.setOnClickListener(p -> open(REGISTRATION));
		}
		ReplyNotificationTargets targets = Preferences.getReplyAlertTargets();
		if (targets != ReplyNotificationTargets.NONE) {
			addHeader(R.string.reply_notification_behavior);
			addButton(R.string.reply_filter, scopeTitle(targets))
					.setOnClickListener(p -> Choice.show(this, Choice.SOURCES, null));
			boolean quiet = Preferences.isReplyPushQuietHoursEnabled()
					&& Preferences.getReplyPushQuietHoursStart() != Preferences.getReplyPushQuietHoursEnd();
			String interval = quiet ? formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursStart()) + " – "
					+ formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursEnd()) : getString(R.string.disabled);
			addButton(getString(R.string.reply_push_quiet_hours), interval).setOnClickListener(p -> open(QUIET));
		}
		addHeader(R.string.reply_device_data);
		addErase();
	}

	private void setMode(Mode next) {
		if (!Preferences.isTrackMyPostsEnabled() || next.server && !ReplyPushManager.isSupported()) return;
		boolean before = Preferences.isReplyPushEnabled();
		if (!before && next.server && ReplyPushManager.isIdentityResetPending(requireContext())) {
			ClickableToast.show(R.string.reply_push_reset_pending);
			return;
		}
		if (!getPreferences().edit().put(Preferences.KEY_TRACKED_REPLIES_LOCAL_CHECK, next.local)
				.put(Preferences.KEY_REPLY_PUSH_ENABLED, next.server).commitChecked()) {
			ClickableToast.show(R.string.unknown_error);
			return;
		}
		if (next.server != before) {
			if (next.server) ReplyPushManager.enable(requireContext());
			else ReplyPushManager.disable(requireContext());
		}
		BackgroundWatcherWorker.updateSchedule(requireContext());
		refreshPreferences();
	}

	private void open(String page) {
		((FragmentHandler) requireActivity()).pushFragment(subpage(page));
	}

	private static int scopeTitle(ReplyNotificationTargets scope) {
		if (scope == ReplyNotificationTargets.FAVORITE) return R.string.reply_source_favorites;
		if (scope == ReplyNotificationTargets.TRACKED) return R.string.reply_source_posts;
		return R.string.reply_source_both;
	}

	private void addNotificationSwitch() {
		ReplyNotificationTargets targets = Preferences.getReplyAlertTargets();
		boolean enabled = targets != ReplyNotificationTargets.NONE;
		int hint = R.string.reply_notify_hint;
		if (enabled && Mode.current() == Mode.MANUAL && targets == ReplyNotificationTargets.TRACKED) {
			hint = R.string.reply_manual_no_alerts;
		} else if (enabled && targets != ReplyNotificationTargets.BOTH) {
			hint = scopeTitle(targets);
		}
		NotificationManager manager = requireContext().getSystemService(NotificationManager.class);
		NotificationChannel channel = manager.getNotificationChannel(C.NOTIFICATION_CHANNEL_REPLIES);
		if (enabled && (!manager.areNotificationsEnabled() ||
				channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
			hint = R.string.reply_permission_blocked;
		}
		CheckPreference toggle = addCheck(false, null, enabled, R.string.reply_sources, hint);
		toggle.setOnClickListener(p -> {
			boolean next = !p.getValue();
			if (Preferences.setReplyAlertsEnabled(next)) {
				if (next) requestNotificationPermission();
				else WatcherNotifications.cancelAllReplies(requireContext());
			} else ClickableToast.show(R.string.unknown_error);
			refreshPreferences();
		});
	}

	private void addQuietHours() {
		CheckPreference enabled = addCheck(true, Preferences.KEY_REPLY_PUSH_QUIET_HOURS_ENABLED,
				Preferences.DEFAULT_REPLY_PUSH_QUIET_HOURS_ENABLED, R.string.reply_push_quiet_hours,
				R.string.reply_push_quiet_hours__summary);
		enabled.setOnAfterChangeListener(p -> refreshPreferences());
		if (enabled.getValue()) {
			addButton(getString(R.string.reply_push_quiet_hours_start), formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursStart()))
					.setOnClickListener(p -> Choice.show(this, Choice.START, null));
			addButton(getString(R.string.reply_push_quiet_hours_end), formatReplyPushQuietTime(Preferences.getReplyPushQuietHoursEnd()))
					.setOnClickListener(p -> Choice.show(this, Choice.END, null));
		}
	}

	private void addRegistration() {
		String id = ReplyPushManager.getInstallationId(requireContext());
		if (!StringUtils.isEmpty(id)) {
			addButton(getString(R.string.reply_push_copy_installation_id), id).setOnClickListener(p -> {
				StringUtils.copyToClipboard(requireContext(), id);
				ClickableToast.show(R.string.reply_push_installation_id_copied);
			});
		}
		addReplyPushIdentityResetPreference();
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
		resetPreference.setOnClickListener(p -> Choice.show(this, Choice.RESET, null));
		if (isResumed() && (pending || cooldownRemaining > 0L || !configured))
			requireView().postDelayed(refresh, pending ? 1000L : !configured ? 5000L : Math.min(cooldownRemaining, 60_000L));
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

	public static class Choice extends DialogFragment {
		private static final String METHOD = "method", CONSENT = "consent", SOURCES = "sources", ERASE = "erase",
				RESET = "reset", START = "start", END = "end";
		private boolean selectionMade;

		private boolean takeSelection() {
			if (selectionMade) return false;
			selectionMade = true;
			return true;
		}

		static void show(ReplyNotificationsFragment owner, String kind, Mode mode) {
			FragmentManager manager = owner.getChildFragmentManager();
			if (manager.isStateSaved() || manager.isDestroyed() || manager.findFragmentByTag(kind) != null) return;
			Choice dialog = new Choice();
			Bundle args = new Bundle();
			args.putString("kind", kind);
			if (mode != null) args.putString("mode", mode.name());
			dialog.setArguments(args);
			dialog.showNow(manager, kind);
		}

		@NonNull @Override public AlertDialog onCreateDialog(Bundle state) {
			ReplyNotificationsFragment owner = (ReplyNotificationsFragment) requireParentFragment();
			String kind = requireArguments().getString("kind");
			MotionDialogBuilder b = MotionDialogBuilder.standard(requireContext(), ERASE.equals(kind) || RESET.equals(kind));
			b.setNegativeButton(android.R.string.cancel, null);
			switch (kind) {
				case METHOD: {
					Mode[] modes = ReplyPushManager.isSupported() ? Mode.values() : new Mode[] {Mode.MANUAL, Mode.LOCAL};
					String[] titles = new String[modes.length];
					for (int i = 0; i < modes.length; i++) titles[i] = getString(modes[i].title);
					return b.setTitle(R.string.reply_method).setSingleChoiceItems(titles, Mode.current().ordinal(), (d, i) -> {
						if (!takeSelection()) return;
						Mode next = modes[i];
						d.dismiss();
						if (next.server && !Mode.current().server) show(owner, CONSENT, next);
						else owner.setMode(next);
					}).create();
				}
				case CONSENT:
					return b.setTitle(R.string.reply_push).setMessage(R.string.reply_push_consent__message)
							.setPositiveButton(R.string.enable, (d, i) -> owner.setMode(Mode.valueOf(requireArguments().getString("mode")))).create();
				case SOURCES: {
					ReplyNotificationTargets[] scopes = {ReplyNotificationTargets.BOTH, ReplyNotificationTargets.TRACKED,
							ReplyNotificationTargets.FAVORITE};
					String[] labels = new String[scopes.length];
					int current = -1;
					for (int i = 0; i < scopes.length; i++) {
						labels[i] = getString(scopeTitle(scopes[i]));
						if (scopes[i] == Preferences.getReplyAlertTargets()) current = i;
					}
					return b.setTitle(R.string.reply_filter).setSingleChoiceItems(labels, current, (d, i) -> {
						if (!takeSelection()) return;
						if (Preferences.getReplyAlertTargets() != ReplyNotificationTargets.NONE &&
								!Preferences.writeReplyAlerts(true, scopes[i])) ClickableToast.show(R.string.unknown_error);
						d.dismiss();
						owner.refreshPreferences();
					}).create();
				}
				case ERASE:
					return b.setTitle(R.string.reply_erase_confirm).setMessage(R.string.reply_erase_message)
							.setPositiveButton(R.string.delete, (d, i) -> owner.eraseHistory()).create();
				case RESET:
					return b.setTitle(R.string.reply_push_reset_identity).setMessage(getString(R.string.reply_push_reset_identity__message)
							+ "\n\n" + getString(R.string.reply_push_reset_identity__cooldown))
							.setPositiveButton(R.string.reply_push_reset_identity, (d, i) -> {
								ClickableToast.show(ReplyPushManager.resetIdentity(requireContext())
										? R.string.reply_push_reset_started : R.string.reply_push_reset_unavailable);
								owner.refreshPreferences();
							}).create();
				case START:
				case END: {
					boolean start = START.equals(kind);
					int minutes = start ? Preferences.getReplyPushQuietHoursStart() : Preferences.getReplyPushQuietHoursEnd();
					TimePickerDialog picker = new TimePickerDialog(requireContext(), (v, h, m) -> {
						int value = h * 60 + m;
						if (value == (start ? Preferences.getReplyPushQuietHoursEnd() : Preferences.getReplyPushQuietHoursStart())) {
							ClickableToast.show(R.string.reply_quiet_equal);
							return;
						}
						if (start) Preferences.setReplyPushQuietHoursStart(value);
						else Preferences.setReplyPushQuietHoursEnd(value);
						owner.refreshPreferences();
					}, minutes / 60, minutes % 60, DateFormat.is24HourFormat(requireContext()));
					com.mishiranu.dashchan.content.security.AppSecurity.protectDialog(picker);
					return picker;
				}
				default: throw new IllegalArgumentException(kind);
			}
		}
	}

	private void requestNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && requireContext()
				.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			permission.launch(Manifest.permission.POST_NOTIFICATIONS);
		}
	}
}
