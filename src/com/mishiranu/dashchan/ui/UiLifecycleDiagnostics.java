package com.mishiranu.dashchan.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.strictmode.FragmentStrictMode;
import com.mishiranu.dashchan.BuildConfig;

/** Debug-only, local diagnostics. Never log arguments, URIs, drafts or Fragment.toString(). */
public final class UiLifecycleDiagnostics {
	private static final String TAG = "UiLifecycle";

	private UiLifecycleDiagnostics() {}

	public static void event(Object owner, String event) {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, owner.getClass().getSimpleName() + "@"
					+ Integer.toHexString(System.identityHashCode(owner)) + " " + event);
		}
	}

	/** Called only by the UI host, before FragmentActivity restores its fragments. */
	public static void install(FragmentActivity activity) {
		if (!BuildConfig.DEBUG) return;
		FragmentManager manager = activity.getSupportFragmentManager();
		manager.setStrictModePolicy(new FragmentStrictMode.Policy.Builder()
				.detectFragmentReuse()
				.detectRetainInstanceUsage()
				.detectTargetFragmentUsage()
				.detectSetUserVisibleHint()
				.detectWrongNestedHierarchy()
				.detectWrongFragmentContainer()
				// Log the violation category, not its message (which can contain fragment arguments).
				.penaltyListener(violation -> event(violation.getFragment(),
						"violation=" + violation.getClass().getSimpleName()))
				.build());
		manager.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
			@Override public void onFragmentCreated(@NonNull FragmentManager fm,
					@NonNull Fragment fragment, Bundle state) {
				event(fragment, "created restored=" + (state != null));
			}
			@Override public void onFragmentViewCreated(@NonNull FragmentManager fm,
					@NonNull Fragment fragment, @NonNull View view, Bundle state) {
				event(fragment, "view_created restored=" + (state != null));
			}
			@Override public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
				event(fragment, "resumed");
			}
			@Override public void onFragmentPaused(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
				event(fragment, "paused");
			}
			@Override public void onFragmentViewDestroyed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
				event(fragment, "view_destroyed");
			}
			@Override public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
				event(fragment, "destroyed");
			}
		}, true);
		event(activity, "diagnostics_enabled");
	}
}
