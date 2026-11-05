package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import android.os.Bundle;
import android.os.Build;
import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

public abstract class StateActivity extends FragmentActivity {
	private OnBackPressedCallback systemBackCallback;
	private Object priorityBackRegistration;

	public static class InstanceFragment extends Fragment {
		@Override
		public void onDetach() {
			try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/StateActivity/instanceDetach")) {
				((StateActivity) getActivity()).callOnFinish(true, "instance_detach");
				super.onDetach();
				scope.result("ok");
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		systemBackCallback = new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackStarted(BackEventCompat backEvent) {
				onSystemBackStarted(backEvent);
			}

			@Override
			public void handleOnBackProgressed(BackEventCompat backEvent) {
				onSystemBackProgressed(backEvent);
			}

			@Override
			public void handleOnBackCancelled() {
				onSystemBackCancelled();
			}

			@Override
			public void handleOnBackPressed() {
				onSystemBackPressed();
			}
		};
		getOnBackPressedDispatcher().addCallback(this, systemBackCallback);
		updateSystemBackCallback();

		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/StateActivity/prepareInstance")
				.count(savedInstanceState != null ? 1 : 0)) {
			String tag = "instance";
			FragmentManager fragmentManager = getSupportFragmentManager();
			InstanceFragment fragment = (InstanceFragment) fragmentManager.findFragmentByTag(tag);
			boolean existed = fragment != null;
			if (fragment == null) {
				fragment = new InstanceFragment();
				// This fragment holds no state. A regular fragment also detaches on recreation,
				// which is exactly when the old activity must release its subscriptions.
				fragmentManager.beginTransaction().add(fragment, tag).commit();
			}
			scope.result(existed ? "reused" : "enqueued");
		}
	}

	protected boolean isSystemPredictiveBackEnabled() {
		return false;
	}

	protected boolean shouldHandleSystemBack() {
		return !isSystemPredictiveBackEnabled();
	}

	/** A screen-specific Back destination can take precedence over IME dismissal. */
	protected boolean shouldPrioritizeSystemBackOverIme() { return false; }

	public final void updateSystemBackCallback() {
		if (systemBackCallback != null) {
			systemBackCallback.setEnabled(shouldHandleSystemBack());
			boolean priority = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
					&& !isFinishing() && systemBackCallback.isEnabled() && shouldPrioritizeSystemBackOverIme();
			if (Build.VERSION.SDK_INT >= 33) {
				if (priority && priorityBackRegistration == null) {
					priorityBackRegistration = Build.VERSION.SDK_INT >= 34 ? Api34.register(this) : Api33.register(this);
				} else if (!priority && priorityBackRegistration != null) {
					Api33.unregister(priorityBackRegistration); priorityBackRegistration = null;
				}
			}
		}
	}

	@Override public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus); updateSystemBackCallback();
	}
	@Override protected void onStart() {
		super.onStart(); updateSystemBackCallback();
	}

	@androidx.annotation.RequiresApi(33)
	private static final class Api33 {
		static Object register(StateActivity activity) {
			android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
			android.window.OnBackInvokedCallback callback = activity::onSystemBackPressed;
			dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
			return new Object[]{dispatcher, callback};
		}
		static void unregister(Object registration) {
			Object[] values = (Object[]) registration;
			((android.window.OnBackInvokedDispatcher) values[0]).unregisterOnBackInvokedCallback(
					(android.window.OnBackInvokedCallback) values[1]);
		}
	}
	@androidx.annotation.RequiresApi(34)
	private static final class Api34 {
		static Object register(StateActivity activity) {
			android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
			android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
				private BackEventCompat compatEvent(android.window.BackEvent event) {
					return new BackEventCompat(event.getTouchX(), event.getTouchY(), event.getProgress(), event.getSwipeEdge());
				}
				@Override public void onBackStarted(android.window.BackEvent event) { activity.onSystemBackStarted(compatEvent(event)); }
				@Override public void onBackProgressed(android.window.BackEvent event) { activity.onSystemBackProgressed(compatEvent(event)); }
				@Override public void onBackCancelled() { activity.onSystemBackCancelled(); }
				@Override public void onBackInvoked() { activity.onSystemBackPressed(); }
			};
			dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
			return new Object[]{dispatcher, callback};
		}
	}

	protected void onSystemBackStarted(BackEventCompat backEvent) {}

	protected void onSystemBackProgressed(BackEventCompat backEvent) {}

	protected void onSystemBackCancelled() {}

	protected void onSystemBackPressed() {
		performDefaultBack();
	}

	protected final void performDefaultBack() {
		if (systemBackCallback == null) {
			return;
		}
		systemBackCallback.setEnabled(false);
		try {
			getOnBackPressedDispatcher().onBackPressed();
		} finally {
			// Fallback may reenter lifecycle/navigation and change the applicable policy.
			updateSystemBackCallback();
		}
	}

	private boolean onFinishCalled = false;

	@Override
	public void recreate() {
		super.recreate();
		callOnFinish(true, "recreate");
	}

	@Override
	protected void onPause() {
		super.onPause();
		callOnFinish(false, "pause");
	}

	@Override
	protected void onStop() {
		super.onStop();
		updateSystemBackCallback();
		callOnFinish(false, "stop");
		AuditDiagnostics.requestSummary();
	}

	@Override
	protected void onDestroy() {
		if (priorityBackRegistration != null && Build.VERSION.SDK_INT >= 33) {
			Api33.unregister(priorityBackRegistration); priorityBackRegistration = null;
		}
		super.onDestroy();
		callOnFinish(false, "destroy");
	}

	private void callOnFinish(boolean force, String reason) {
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/StateActivity/finishGate")
				.reason(reason).count((force ? 1 : 0) | (onFinishCalled ? 2 : 0)
						| (isFinishing() ? 4 : 0) | (isChangingConfigurations() ? 8 : 0))) {
			if (!onFinishCalled && (isFinishing() || force)) {
				// Claim before callbacks: cleanup may itself trigger lifecycle/navigation.
				onFinishCalled = true;
				UiLifecycleDiagnostics.event(this, "finish_cleanup forced=" + force);
				onFinish();
				scope.result("performed");
			} else {
				scope.result("skipped");
			}
		}
	}

	protected void onFinish() {}
}
