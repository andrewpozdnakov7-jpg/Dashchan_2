package com.mishiranu.dashchan.ui;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import android.os.Bundle;
import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

public abstract class StateActivity extends FragmentActivity {
	private OnBackPressedCallback systemBackCallback;

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

	public final void updateSystemBackCallback() {
		if (systemBackCallback != null) {
			systemBackCallback.setEnabled(shouldHandleSystemBack());
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
		boolean enabled = systemBackCallback.isEnabled();
		systemBackCallback.setEnabled(false);
		try {
			getOnBackPressedDispatcher().onBackPressed();
		} finally {
			systemBackCallback.setEnabled(enabled);
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
		callOnFinish(false, "stop");
		AuditDiagnostics.requestSummary();
	}

	@Override
	protected void onDestroy() {
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
