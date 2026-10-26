package com.mishiranu.dashchan.ui;

import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.ui.preference.CrashReportDialogs;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.CrashReports;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One automatic offer per process launch, only in an unlocked, resumed, focused UI. */
public final class CrashReportPrompt {
	private static final AtomicBoolean OFFERED = new AtomicBoolean();
	private CrashReportPrompt() {}
	/** Also called on restored report dialogs, so rotation/process recreation cannot enqueue a second offer. */
	public static void reportInteraction() { OFFERED.set(true); }
	public static void install(MainActivity activity, BooleanSupplier ready) {
		View decor = activity.getWindow().getDecorView();
		class Queue implements DefaultLifecycleObserver, ViewTreeObserver.OnWindowFocusChangeListener, Runnable {
			boolean checking;
			final FragmentManager.FragmentLifecycleCallbacks callbacks = new FragmentManager.FragmentLifecycleCallbacks() {
				@Override public void onFragmentDetached(@NonNull FragmentManager manager, @NonNull Fragment fragment) { schedule(); }
			};
			void schedule() { decor.removeCallbacks(this); decor.post(this); }
			boolean available() {
				FragmentManager manager = activity.getSupportFragmentManager();
				if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()
						|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
						|| !decor.hasWindowFocus() || !ready.getAsBoolean() || SecurityStore.locked(activity)) return false;
				for (Fragment fragment : manager.getFragments()) if (fragment instanceof DialogFragment && !fragment.isRemoving()) return false;
				return true;
			}
			@Override public void run() {
				if (checking || OFFERED.get() || !CrashReports.ready() || !Preferences.isCrashReportPromptEnabled() || !available()) return;
				checking = true;
				CrashReports.execute(() -> {
					String id;
					try { id = CrashReports.nextUnseen(); } catch (IOException | RuntimeException e) { id = null; }
					String report = id;
					ConcurrentUtils.HANDLER.post(() -> {
						checking = false;
						if (report == null || !Preferences.isCrashReportPromptEnabled() || !available() || OFFERED.get()) return;
						try {
							// Persist before display: rotation/background/process recreation must not spam the same offer.
							if (CrashReports.markOffered(report) && OFFERED.compareAndSet(false, true)) {
								CrashReportDialogs.offer(activity, report);
							}
						} catch (IOException | IllegalStateException ignored) { /* Report remains in the manual list. */ }
					});
				});
			}
			@Override public void onResume(@NonNull LifecycleOwner owner) { schedule(); }
			@Override public void onPause(@NonNull LifecycleOwner owner) { decor.removeCallbacks(this); }
			@Override public void onWindowFocusChanged(boolean focused) { if (focused) schedule(); }
			@Override public void onDestroy(@NonNull LifecycleOwner owner) {
				decor.removeCallbacks(this); CrashReports.unlisten(this);
				if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
				activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(callbacks);
				activity.getLifecycle().removeObserver(this);
			}
		}
		Queue queue = new Queue();
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(queue.callbacks, false);
		decor.getViewTreeObserver().addOnWindowFocusChangeListener(queue);
		CrashReports.listen(queue);
		activity.getLifecycle().addObserver(queue);
	}
}
