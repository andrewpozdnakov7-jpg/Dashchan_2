package com.mishiranu.dashchan.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.UpdaterActivity;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.content.update.DownloadedUpdate;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/** Only a resumed, unlocked, focused host may offer or start installation. */
public final class DownloadedUpdatePrompt {
	private static final String TAG = "downloaded-update-prompt";
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final ArrayList<WeakReference<Queue>> QUEUES = new ArrayList<>();
	private DownloadedUpdatePrompt() {}
	/** Called on main after a download completes; never launches from an application/service Context. */
	public static boolean installIfVisible(ArrayList<String> files) {
		for (WeakReference<Queue> reference : QUEUES) {
			Queue queue = reference.get();
			if (queue != null && queue.available()) {
				try { queue.activity.startActivity(UpdaterActivity.createInstallIntent(queue.activity, files)); return true; }
				catch (RuntimeException e) { log("foreground_launch_failed"); }
			}
		}
		return false;
	}
	public static void changed() {
		MAIN.post(() -> {
			QUEUES.removeIf(reference -> reference.get() == null);
			for (WeakReference<Queue> reference : QUEUES) { Queue queue = reference.get(); if (queue != null) queue.schedule(); }
		});
	}
	public static void install(MainActivity activity, BooleanSupplier ready) {
		// Track a visible host in both distributions for the existing extension installer.
		// Only GitHub may read receipts or offer application self-updates below.
		Queue queue = new Queue(activity, ready);
		QUEUES.add(new WeakReference<>(queue));
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(queue.callbacks, false);
		queue.decor.getViewTreeObserver().addOnWindowFocusChangeListener(queue);
		activity.getLifecycle().addObserver(queue);
	}
	private static final class Queue implements DefaultLifecycleObserver, ViewTreeObserver.OnWindowFocusChangeListener, Runnable {
		final MainActivity activity;
		final BooleanSupplier ready;
		final View decor;
		boolean checking;
		final FragmentManager.FragmentLifecycleCallbacks callbacks = new FragmentManager.FragmentLifecycleCallbacks() {
			@Override public void onFragmentDetached(@NonNull FragmentManager manager, @NonNull Fragment fragment) { schedule(); }
		};
		Queue(MainActivity activity, BooleanSupplier ready) { this.activity = activity; this.ready = ready; decor = activity.getWindow().getDecorView(); }
		void schedule() { decor.removeCallbacks(this); decor.post(this); }
		boolean available() {
			FragmentManager manager = activity.getSupportFragmentManager();
			if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()
					|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
					|| !decor.hasWindowFocus() || !ready.getAsBoolean() || SecurityStore.locked(activity)) return false;
			for (Fragment fragment : manager.getFragments()) if (fragment instanceof DialogFragment && !fragment.isRemoving()) return false;
			return true;
		}
		@Override public void onResume(@NonNull LifecycleOwner owner) { schedule(); }
		@Override public void onPause(@NonNull LifecycleOwner owner) { decor.removeCallbacks(this); }
		@Override public void onWindowFocusChanged(boolean focused) { if (focused) schedule(); }
		@Override public void onDestroy(@NonNull LifecycleOwner owner) {
			decor.removeCallbacks(this);
			if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
			activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(callbacks);
			activity.getLifecycle().removeObserver(this);
			QUEUES.removeIf(reference -> reference.get() == null || reference.get() == this);
		}
		@Override public void run() {
			if (!BuildConfig.ALLOW_APPLICATION_SELF_UPDATE || checking || !available() || UpdaterActivity.isDownloadActive()) return;
			try { DownloadedUpdate.launch(activity); }
			catch (IOException e) { log("launch_save_failed"); return; }
			DownloadedUpdate.Entry entry = DownloadedUpdate.read(activity);
			if (entry == null) return;
			if (entry.code > BuildConfig.VERSION_CODE && !DownloadedUpdate.due(entry)) return;
			checking = true;
			Context context = activity.getApplicationContext();
			ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
				DownloadedUpdate.Check check = DownloadedUpdate.check(context, entry);
				MAIN.post(() -> {
					checking = false;
					DownloadedUpdate.Entry current = DownloadedUpdate.read(context);
					if (current == null || !current.id.equals(entry.id) || UpdaterActivity.isDownloadActive()) return;
					if (check != DownloadedUpdate.Check.READY) {
						try { DownloadedUpdate.clear(context, entry.id); }
						catch (IOException e) { log("clear_failed"); }
						if (check != DownloadedUpdate.Check.INSTALLED && available()) {
							Toast.makeText(activity, R.string.downloaded_update_unavailable, Toast.LENGTH_LONG).show();
						}
						return;
					}
					if (!available()) return;
					try {
						if (DownloadedUpdate.claim(context, entry.id)) {
							ReadyDialog dialog = new ReadyDialog();
							Bundle args = new Bundle(); args.putString("id", entry.id); args.putString("version", entry.version);
							dialog.setArguments(args); dialog.showNow(activity.getSupportFragmentManager(), TAG);
						}
					} catch (IOException e) { log("offer_save_failed"); }
				});
			});
		}
	}
	private static void log(String event) { android.util.Log.w("UpdateInstaller", event); }
	/** Restored by FragmentManager on rotation; no Activity or confirmation Intent is persisted. */
	public static final class ReadyDialog extends DialogFragment {
		@Override public void onCreate(Bundle state) { super.onCreate(state); setCancelable(false); }
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			return new MotionDialogBuilder(requireContext()).setTitle(R.string.update_downloaded)
					.setMessage(getString(R.string.downloaded_update_ready, requireArguments().getString("version")))
					.setPositiveButton(R.string.downloaded_update_install, null)
					.setNegativeButton(R.string.downloaded_update_later, null).create();
		}
		@Override public void onStart() {
			super.onStart();
			if (!BuildConfig.ALLOW_APPLICATION_SELF_UPDATE) { dismiss(); return; }
			DownloadedUpdate.Entry entry = DownloadedUpdate.read(requireContext());
			if (entry == null || !entry.id.equals(requireArguments().getString("id")) || entry.code <= BuildConfig.VERSION_CODE) {
				dismiss(); return;
			}
			android.app.AlertDialog dialog = (android.app.AlertDialog) requireDialog();
			dialog.setCanceledOnTouchOutside(false);
			dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> act(true));
			dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> act(false));
		}
		private void act(boolean install) {
			if (!BuildConfig.ALLOW_APPLICATION_SELF_UPDATE || SecurityStore.locked(requireContext())) return;
			String id = requireArguments().getString("id");
			DownloadedUpdate.Entry entry = DownloadedUpdate.read(requireContext());
			if (entry == null || !entry.id.equals(id)) { dismiss(); return; }
			try {
				if (!install && !DownloadedUpdate.postpone(requireContext(), id)) { dismiss(); return; }
				if (install) {
					startActivity(UpdaterActivity.createInstallIntent(requireContext(), new ArrayList<>(Collections.singletonList(entry.file))));
				}
				dismiss();
			} catch (IOException | RuntimeException e) {
				log("offer_action_failed");
				Toast.makeText(requireContext(), R.string.unknown_error, Toast.LENGTH_LONG).show();
			}
		}
	}
}
