package com.mishiranu.dashchan.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TextAppearanceSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.ui.preference.PageCleanupSettingsFragment;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.function.BooleanSupplier;

/** At most three presentations, one per Activity launch; explicit choices finish the announcement. */
public final class PageCleanupNotice {
	private static final String TAG = "page-cleanup-notice-v1";
	static final String KEY_COUNT = "page-cleanup-presentations-v1";
	static final String KEY_ACK = "page-cleanup-acknowledged-v1";
	private PageCleanupNotice() {}

	/** SavedStateHandle retains the launch claim across rotation and background process restoration. */
	public static final class LaunchState extends ViewModel {
		private final SavedStateHandle state;
		public LaunchState(SavedStateHandle state) { this.state = state; }
		boolean claimed() { return Boolean.TRUE.equals(state.get(TAG)); }
		void claim() { state.set(TAG, true); }
	}

	public static void install(MainActivity activity, BooleanSupplier ready) {
		LaunchState launch = new ViewModelProvider(activity).get(LaunchState.class);
		SharedPreferences preferences = StartupNotices.store(activity);
		if (Preferences.isPageCleanupEnabled()) {
			// A user who already enabled it has already discovered and chosen the feature.
			preferences.edit().putBoolean(KEY_ACK, true).apply();
			return;
		}
		if (!PageCleanupNoticePolicy.eligible(preferences.getInt(KEY_COUNT, 0),
				preferences.getBoolean(KEY_ACK, false), false, launch.claimed())) return;
		View decor = activity.getWindow().getDecorView();
		class Queue implements DefaultLifecycleObserver, ViewTreeObserver.OnWindowFocusChangeListener, Runnable {
			boolean closed;
			int retries;
			final FragmentManager.FragmentLifecycleCallbacks callbacks = new FragmentManager.FragmentLifecycleCallbacks() {
				@Override public void onFragmentDetached(@NonNull FragmentManager manager, @NonNull Fragment fragment) { schedule(); }
				@Override public void onFragmentResumed(@NonNull FragmentManager manager, @NonNull Fragment fragment) { schedule(); }
			};
			void schedule() {
				if (closed) return;
				decor.removeCallbacks(this); retries = 6; decor.postDelayed(this, 500);
			}
			void close() {
				if (closed) return;
				closed = true; decor.removeCallbacks(this);
				if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
				activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(callbacks);
				activity.getLifecycle().removeObserver(this);
			}
			@Override public void onResume(@NonNull LifecycleOwner owner) { schedule(); }
			@Override public void onPause(@NonNull LifecycleOwner owner) { decor.removeCallbacks(this); }
			@Override public void onDestroy(@NonNull LifecycleOwner owner) { close(); }
			@Override public void onWindowFocusChanged(boolean focused) { if (focused) schedule(); }
			@Override public void run() {
				if (closed) return;
				FragmentManager manager = activity.getSupportFragmentManager();
				if (!PageCleanupNoticePolicy.eligible(preferences.getInt(KEY_COUNT, 0),
						preferences.getBoolean(KEY_ACK, false), Preferences.isPageCleanupEnabled(), launch.claimed())) {
					close(); return;
				}
				if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()
						|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
						|| !decor.hasWindowFocus() || SecurityStore.locked(activity)) return;
				if (!ready.getAsBoolean()) {
					if (retries-- > 0) decor.postDelayed(this, 500);
					return;
				}
				if (manager.findFragmentByTag(TAG) != null) { launch.claim(); close(); return; }
				for (Fragment fragment : manager.getFragments()) {
					if (fragment instanceof DialogFragment && !fragment.isRemoving()) return;
				}
				launch.claim();
				new NoticeDialog().showNow(manager, TAG);
				close();
			}
		}
		Queue queue = new Queue();
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(queue.callbacks, false);
		decor.getViewTreeObserver().addOnWindowFocusChangeListener(queue);
		activity.getLifecycle().addObserver(queue);
	}

	public static final class NoticeDialog extends DialogFragment {
		private boolean counted, acknowledged;
		@Override public void onCreate(Bundle state) {
			super.onCreate(state);
			counted = state != null && state.getBoolean("counted", false);
			setCancelable(true);
			new ViewModelProvider(requireActivity()).get(LaunchState.class).claim();
		}
		@Override public void onSaveInstanceState(@NonNull Bundle state) {
			super.onSaveInstanceState(state);
			state.putBoolean("counted", counted);
		}
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			Context host = requireContext();
			boolean light = ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.LIGHT;
			Context themed = new ContextThemeWrapper(host, light
					? R.style.Theme_Slooop_StartupNotice_Light : R.style.Theme_Slooop_StartupNotice_Dark);
			SpannableStringBuilder message = new SpannableStringBuilder(getString(R.string.page_cleanup_notice_message));
			message.append("\n\n");
			int start = message.length();
			message.append(getString(R.string.page_cleanup_notice_limit));
			message.setSpan(new TextAppearanceSpan(themed,
					com.google.android.material.R.style.TextAppearance_Material3_BodySmall),
					start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			return new MaterialAlertDialogBuilder(themed).setTitle(R.string.page_cleanup).setMessage(message)
					.setNegativeButton(R.string.page_cleanup_notice_keep_off, null)
					.setPositiveButton(R.string.page_cleanup_notice_settings, null).create();
		}
		@Override public void onStart() {
			super.onStart();
			SharedPreferences preferences = StartupNotices.store(requireContext());
			if (preferences.getBoolean(KEY_ACK, false) || Preferences.isPageCleanupEnabled()
					|| SecurityStore.locked(requireContext())) {
				dismiss(); return;
			}
			if (!counted) {
				int previous = preferences.getInt(KEY_COUNT, 0);
				if (!PageCleanupNoticePolicy.eligible(previous, false, false, false)) { dismiss(); return; }
				if (!preferences.edit().putInt(KEY_COUNT, PageCleanupNoticePolicy.nextCount(previous)).commit()) {
					Toast.makeText(requireContext(), R.string.page_cleanup_notice_save_failed, Toast.LENGTH_LONG).show();
					dismiss(); return;
				}
				counted = true;
			}
			AlertDialog dialog = (AlertDialog) requireDialog();
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> choose(false));
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> choose(true));
		}
		private void choose(boolean settings) {
			if (acknowledged || !counted || getParentFragmentManager().isStateSaved()
					|| !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
					|| SecurityStore.locked(requireContext())) return;
			MainActivity host = (MainActivity) requireActivity();
			if (!StartupNotices.store(host).edit().putBoolean(KEY_ACK, true).commit()) {
				Toast.makeText(host, R.string.page_cleanup_notice_save_failed, Toast.LENGTH_LONG).show(); return;
			}
			acknowledged = true;
			dismissNow();
			if (settings && !host.isFinishing() && !host.isDestroyed()) host.pushFragment(new PageCleanupSettingsFragment());
		}
	}
}
