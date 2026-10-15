package com.mishiranu.dashchan.content.security;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.MainActivity;
import java.util.concurrent.atomic.AtomicBoolean;

/** A clean process with no initialized application stores or posting/download writers. */
public final class PrivacyResetActivity extends ComponentActivity {
	private final AtomicBoolean running = new AtomicBoolean();
	private TextView message;
	private LinearLayout layout;
	private boolean cleanupCompleted;
	private volatile boolean resumed;
	private volatile int authorizationGeneration;
	@Override protected void onResume() {
		super.onResume(); resumed = true;
		if (cleanupCompleted) openEmptyProfile();
	}
	@Override protected void onPause() { resumed = false; authorizationGeneration++; super.onPause(); }
	private boolean authorized(int generation) {
		return resumed && generation == authorizationGeneration && !isDestroyed() && !isFinishing()
				&& getSystemService(android.os.PowerManager.class).isInteractive();
	}
	private final androidx.activity.result.ActivityResultLauncher<Intent> restoreFolder = registerForActivityResult(
			new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
		if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
		android.net.Uri selected = result.getData().getData();
		try {
			String expected = SecurityStore.update(this, s -> s.optBoolean("reset_pending") ? s.optString("reset_tree", null) : null);
			int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
			if (expected == null || !expected.equals(selected.toString()) || (result.getData().getFlags() & required) != required) {
				message.setText(R.string.security_restore_same_folder); return;
			}
			getContentResolver().takePersistableUriPermission(selected, required);
			runCleanup();
		} catch (Exception e) { message.setText(R.string.security_downloads_failed); }
	});
	@Override protected void onCreate(Bundle state) {
		super.onCreate(state);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() { moveTaskToBack(true); }
		});
		layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER);
		int padding = Math.round(24 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, padding);
		message = new TextView(this); message.setGravity(Gravity.CENTER);
		layout.addView(message);
		android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true);
		scroll.addView(layout, new android.widget.ScrollView.LayoutParams(-1, -2));
		scroll.setOnApplyWindowInsetsListener((view, insets) -> {
			android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
			view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
		});
		setContentView(scroll);
		runCleanup();
	}

	private void runCleanup() {
		if (!running.compareAndSet(false, true)) return;
		cleanupCompleted = false;
		layout.removeAllViews(); layout.addView(message); message.setText(R.string.security_opening);
		new Thread(() -> {
			try { PrivacyReset.run(this); runOnUiThread(() -> { cleanupCompleted = true; openEmptyProfile(); }); }
			catch (Exception e) { runOnUiThread(this::failed); }
			finally { running.set(false); }
		}, "privacy-reset").start();
	}

	private void openEmptyProfile() {
		if (!resumed || isDestroyed() || isFinishing()) return;
		try {
			String token = SecurityStore.update(this, s -> {
				int boot = android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
				if (s.optBoolean("reset_pending") || !s.has("empty_launch") || boot < 0
						|| boot != s.optInt("empty_launch_boot", -1)
						|| s.optLong("empty_launch_generation", -1) != s.optLong("profile_generation")) {
					throw new java.io.IOException("No active profile reset receipt");
				}
				// A long background stay must not strand the recovery UI or extend its
				// one-shot authentication grant. Resume normally, requiring the main PIN.
				return android.os.SystemClock.elapsedRealtime() <= s.optLong("empty_launch_until")
						? s.getString("empty_launch") : null;
			});
			PrivacyReset.stopOtherProcesses(this);
			startActivity(new Intent(this, MainActivity.class).putExtra("empty_profile_token", token)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
			finishAndRemoveTask();
		} catch (Exception e) { failed(); }
	}

	private void failed() {
		if (isDestroyed()) return;
		message.setText(R.string.security_reset_failed);
		Button retry = new Button(this); retry.setText(R.string.security_retry_cleanup);
		retry.setOnClickListener(v -> runCleanup()); layout.addView(retry);
		try {
			boolean externalOnly = SecurityStore.update(this, s -> s.optBoolean("reset_local_done") && s.has("reset_tree"));
			if (!externalOnly) return;
			message.setText(R.string.security_downloads_failed);
			Button restore = new Button(this); restore.setText(R.string.security_restore_folder_access);
			restore.setOnClickListener(v -> {
				try {
					String expected = SecurityStore.update(this, s -> s.optString("reset_tree", null));
					if (expected == null) return;
					restoreFolder.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
							.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(expected))
							.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
									| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
				} catch (Exception e) { message.setText(R.string.security_downloads_failed); }
			});
			layout.addView(restore);
			EditText pin = new EditText(this); pin.setHint(R.string.security_current_pin);
			AppLockActivity.configurePinInput(pin);
			layout.addView(pin);
			Button skip = new Button(this); skip.setText(R.string.security_skip_downloads);
			skip.setFilterTouchesWhenObscured(true);
			skip.setOnClickListener(v -> {
				if (!running.compareAndSet(false, true)) return;
				char[] digits = new char[pin.length()]; pin.getText().getChars(0, digits.length, digits, 0); pin.setText("");
				int generation = authorizationGeneration;
				new Thread(() -> {
					try {
						if (SecurityStore.verify(this, digits, true) == SecurityStore.Result.PRIMARY) {
							SecurityStore.authorizedUpdate(this, () -> authorized(generation), s -> { s.put("external_cleanup_incomplete", true); return null; });
							PrivacyReset.complete(this, () -> authorized(generation));
							runOnUiThread(() -> { if (authorized(generation)) openEmptyProfile(); });
						} else runOnUiThread(() -> pin.setError(getString(R.string.security_wrong_pin)));
					} catch (Exception e) { runOnUiThread(() -> pin.setError(getString(R.string.security_unavailable))); }
					finally { PinCredential.clear(digits); running.set(false); }
				}, "privacy-reset-recovery").start();
			});
			layout.addView(skip);
		} catch (Exception ignored) {}
	}
}
