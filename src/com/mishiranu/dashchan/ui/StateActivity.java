package com.mishiranu.dashchan.ui;

import androidx.fragment.app.FragmentActivity;

public abstract class StateActivity extends FragmentActivity {
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
