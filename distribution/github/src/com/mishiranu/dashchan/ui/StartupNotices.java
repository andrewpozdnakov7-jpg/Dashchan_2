package com.mishiranu.dashchan.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.function.BooleanSupplier;

/** GitHub-only launch notice. The F-Droid bridge has no notice implementation or resources. */
public final class StartupNotices {
	private static final String TAG = "material3-startup-notice";
	private static final String STORE = "startup-notices";
	private static final String KEY = "material3-acknowledgements";
	private static final StartupNoticePolicy PROCESS = new StartupNoticePolicy();
	private StartupNotices() {}

	static SharedPreferences store(Context context) {
		return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
	}

	public static void install(MainActivity activity, BooleanSupplier ready) {
		View decor = activity.getWindow().getDecorView();
		class Queue implements DefaultLifecycleObserver, ViewTreeObserver.OnWindowFocusChangeListener, Runnable {
			final FragmentManager.FragmentLifecycleCallbacks callbacks = new FragmentManager.FragmentLifecycleCallbacks() {
				@Override public void onFragmentDetached(@NonNull FragmentManager manager, @NonNull Fragment fragment) {
					schedule();
				}
			};
			void schedule() { decor.removeCallbacks(this); decor.post(this); }
			@Override public void onResume(@NonNull LifecycleOwner owner) { schedule(); }
			@Override public void onWindowFocusChanged(boolean focused) { if (focused) schedule(); }
			@Override public void onPause(@NonNull LifecycleOwner owner) { decor.removeCallbacks(this); }
			@Override public void onDestroy(@NonNull LifecycleOwner owner) {
				decor.removeCallbacks(this);
				if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
				activity.getLifecycle().removeObserver(this);
				activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(callbacks);
			}
			@Override public void run() {
				FragmentManager manager = activity.getSupportFragmentManager();
				if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()
						|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
						|| !decor.hasWindowFocus() || !ready.getAsBoolean()) return;
				if (manager.findFragmentByTag(TAG) != null) { PROCESS.restoreVisible(); return; }
				for (Fragment fragment : manager.getFragments()) {
					if (fragment instanceof DialogFragment && !fragment.isRemoving()) return;
				}
				if (PROCESS.claim(store(activity).getInt(KEY, 0))) new NoticeDialog().showNow(manager, TAG);
			}
		}
		Queue queue = new Queue();
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(queue.callbacks, false);
		decor.getViewTreeObserver().addOnWindowFocusChangeListener(queue);
		activity.getLifecycle().addObserver(queue);
	}

	/** Public no-argument Fragment supports normal FragmentManager state restoration. */
	public static final class NoticeDialog extends DialogFragment {
		private int targetCount;
		@Override public void onCreate(Bundle state) {
			super.onCreate(state);
			setCancelable(false);
			PROCESS.restoreVisible();
			targetCount = state != null ? state.getInt("targetCount", 0) : 0;
		}
		@Override public void onSaveInstanceState(@NonNull Bundle state) {
			super.onSaveInstanceState(state);
			state.putInt("targetCount", targetCount);
		}
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			Context host = requireContext();
			boolean light = ThemeEngine.getTheme(host).base == ThemeEngine.Theme.Base.LIGHT;
			Context themed = new ContextThemeWrapper(host, light
					? R.style.Theme_Slooop_StartupNotice_Light : R.style.Theme_Slooop_StartupNotice_Dark);
			AlertDialog dialog = new MaterialAlertDialogBuilder(themed)
					.setTitle(R.string.material3_notice_title).setMessage(R.string.material3_notice_message)
					.setPositiveButton(R.string.material3_notice_read, null).setCancelable(false).create();
			dialog.setCanceledOnTouchOutside(false);
			return dialog;
		}
		@Override public void onStart() {
			super.onStart();
			AlertDialog dialog = (AlertDialog) requireDialog();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
				SharedPreferences preferences = store(requireContext());
				if (targetCount == 0) targetCount = StartupNoticePolicy.nextCount(preferences.getInt(KEY, 0));
				// A checked synchronous commit is intentional: dismissal must not precede durable acknowledgement.
				if (preferences.edit().putInt(KEY, targetCount).commit()) dismiss();
				else Toast.makeText(requireContext(), R.string.material3_notice_save_failed, Toast.LENGTH_LONG).show();
			});
		}
	}
}
