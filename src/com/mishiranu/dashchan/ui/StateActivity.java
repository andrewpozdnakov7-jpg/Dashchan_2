package com.mishiranu.dashchan.ui;

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
			((StateActivity) getActivity()).callOnFinish(true);
			super.onDetach();
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

		String tag = "instance";
		FragmentManager fragmentManager = getSupportFragmentManager();
		InstanceFragment fragment = (InstanceFragment) fragmentManager.findFragmentByTag(tag);
		if (fragment == null) {
			fragment = new InstanceFragment();
			fragment.setRetainInstance(true);
			fragmentManager.beginTransaction().add(fragment, tag).commit();
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
		callOnFinish(true);
	}

	@Override
	protected void onPause() {
		super.onPause();
		callOnFinish(false);
	}

	@Override
	protected void onStop() {
		super.onStop();
		callOnFinish(false);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		callOnFinish(false);
	}

	private void callOnFinish(boolean force) {
		if (!onFinishCalled && (isFinishing() || force)) {
			onFinish();
			onFinishCalled = true;
		}
	}

	protected void onFinish() {}
}
