package com.mishiranu.dashchan.content;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.mishiranu.dashchan.ui.MainActivity;

public class RestartActivity extends Activity {
	private static final long RESTART_DELAY_MS = 250L;

	public static Intent createIntent(Context context) {
		return new Intent(context, RestartActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			Intent intent = new Intent(this, MainActivity.class)
					.setAction(Intent.ACTION_MAIN)
					.addCategory(Intent.CATEGORY_LAUNCHER)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(intent);
			finish();
		}, RESTART_DELAY_MS);
	}
}
