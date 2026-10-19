package com.mishiranu.dashchan.content.security;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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
	private boolean launchReady;
	private String launchToken;
	private volatile boolean resumed;
	private volatile int authorizationGeneration;
	private android.os.CancellationSignal recoveryCancellation;
	private BiometricAttempt recoveryBiometric;
	@Override protected void onResume() {
		super.onResume(); resumed = true;
		if (launchReady) openProfile();
	}
	@Override protected void onPause() {
		resumed = false; authorizationGeneration++;
		android.os.CancellationSignal signal = recoveryCancellation; recoveryCancellation = null;
		BiometricAttempt attempt = recoveryBiometric; recoveryBiometric = null;
		if (signal != null) { signal.cancel(); running.set(false); }
		if (attempt != null) attempt.close();
		super.onPause();
	}
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
		AppSecurity.refreshCaptureWindow(this);
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
		launchReady = false;
		layout.removeAllViews(); layout.addView(message); message.setText(R.string.security_opening);
		new Thread(() -> {
			try {
				// An unreadable startup record gates normal components, but is not evidence
				// that deletion was requested. Once readable, distinguish all three states.
				if (PrivacyReset.recoveryState(this) == PrivacyReset.RecoveryState.RESET_PENDING) PrivacyReset.run(this);
				prepareLaunch();
			}
			catch (Exception e) { runOnUiThread(this::failed); }
			finally { running.set(false); }
		}, "privacy-reset").start();
	}

	private void prepareLaunch() throws java.io.IOException {
		if (PrivacyReset.recoveryState(this) == PrivacyReset.RecoveryState.RESET_PENDING) {
			throw new java.io.IOException("Profile cleanup is still pending");
		}
		// The old main process may have skipped initialization after a transient read
		// failure. Restart it cleanly, without deleting data or inventing an empty-profile receipt.
		// Process waiting and state I/O must never run on the input/UI thread.
		PrivacyReset.stopOtherProcesses(this);
		String token = PrivacyReset.launchToken(this);
		runOnUiThread(() -> {
			if (isDestroyed() || isFinishing()) return;
			launchToken = token; launchReady = true; openProfile();
		});
	}

	private void openProfile() {
		if (!resumed || !launchReady || isDestroyed() || isFinishing()) return;
		try {
			launchReady = false;
			Intent intent = new Intent(this, MainActivity.class)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			// MainActivity revalidates expiry, boot, generation and RAM lease before
			// consuming a real receipt. A normal recovery never bypasses the main PIN.
			if (launchToken != null) intent.putExtra("empty_profile_token", launchToken);
			launchToken = null;
			startActivity(intent);
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
			boolean biometricOnly = SecurityStore.authenticationMode(this) == AuthenticationPolicy.Mode.BIOMETRIC_ONLY;
			EditText pin = new EditText(this); pin.setHint(R.string.security_current_pin);
			AppLockActivity.configurePinInput(pin);
			if (!biometricOnly) layout.addView(pin);
			Button skip = new Button(this); skip.setText(R.string.security_skip_downloads);
			skip.setFilterTouchesWhenObscured(true);
			skip.setOnClickListener(v -> {
				if (!running.compareAndSet(false, true)) return;
				if (biometricOnly) { authenticateRecoverySkip(); return; }
				char[] digits = new char[pin.length()]; pin.getText().getChars(0, digits.length, digits, 0); pin.setText("");
				int generation = authorizationGeneration;
				new Thread(() -> {
					try {
						if (SecurityStore.verify(this, digits, true) == SecurityStore.Result.PRIMARY) {
							SecurityStore.authorizedUpdate(this, () -> authorized(generation), s -> {
								String tree = s.optString("reset_tree", null);
								if (!s.optBoolean("reset_pending") || !s.optBoolean("reset_local_done") || tree == null) {
									throw new java.io.IOException("No external cleanup to skip");
								}
								ExternalCleanupLedger.skipped(s, tree); return null;
							});
							PrivacyReset.complete(this, () -> authorized(generation));
							prepareLaunch();
						} else runOnUiThread(() -> pin.setError(getString(R.string.security_wrong_pin)));
					} catch (Exception e) { runOnUiThread(() -> pin.setError(getString(R.string.security_unavailable))); }
					finally { PinCredential.clear(digits); running.set(false); }
				}, "privacy-reset-recovery").start();
			});
			layout.addView(skip);
		} catch (Exception ignored) {}
	}
	private void authenticateRecoverySkip() {
		try {
			android.hardware.biometrics.BiometricManager manager = getSystemService(android.hardware.biometrics.BiometricManager.class);
			if (manager == null || manager.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
					!= android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) throw new java.io.IOException("Biometrics unavailable");
			final int generation = authorizationGeneration;
			final long lease = SecuritySessionProvider.generation(this);
			final BiometricAttempt attempt = BiometricAttempt.current(this);
			final android.os.CancellationSignal signal = new android.os.CancellationSignal();
			recoveryBiometric = attempt; recoveryCancellation = signal;
			new android.hardware.biometrics.BiometricPrompt.Builder(this)
					.setTitle(getString(R.string.security_skip_downloads))
					.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
					.setNegativeButton(getString(android.R.string.cancel), getMainExecutor(), (dialog, which) -> {})
					.build().authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(attempt.mac), signal,
							getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
						@Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
							if (recoveryBiometric != attempt || recoveryCancellation != signal) { attempt.close(); return; }
							recoveryBiometric = null; recoveryCancellation = null;
							try {
								if (!authorized(generation)) throw new java.io.IOException("Recovery authorization expired");
								attempt.verify(result.getCryptoObject());
								new Thread(() -> {
									try {
										SecurityStore.authorizedUpdate(PrivacyResetActivity.this, () -> authorized(generation)
												&& lease >= 0 && lease == SecuritySessionProvider.generation(PrivacyResetActivity.this), s -> {
											if (SecurityStore.authenticationMode(s) != AuthenticationPolicy.Mode.BIOMETRIC_ONLY
													|| !attempt.matchesCurrent(PrivacyResetActivity.this, s)) throw new java.io.IOException("Recovery credential changed");
											String tree = s.optString("reset_tree", null);
											if (!s.optBoolean("reset_pending") || !s.optBoolean("reset_local_done") || tree == null)
												throw new java.io.IOException("No external cleanup to skip");
											ExternalCleanupLedger.skipped(s, tree); return null;
										});
										PrivacyReset.complete(PrivacyResetActivity.this, () -> authorized(generation));
										prepareLaunch();
									} catch (Exception e) { runOnUiThread(() -> message.setText(R.string.security_unavailable)); }
									finally { attempt.close(); running.set(false); }
								}, "privacy-reset-biometric-recovery").start();
							} catch (Exception e) {
								attempt.close(); running.set(false);
								if (authorized(generation)) message.setText(R.string.security_biometric_retry);
							}
						}
						@Override public void onAuthenticationError(int code, CharSequence error) {
							if (recoveryBiometric != attempt || recoveryCancellation != signal) { attempt.close(); return; }
							recoveryBiometric = null; recoveryCancellation = null; attempt.close(); running.set(false);
							if (authorized(generation)) message.setText(R.string.security_biometric_retry);
						}
					});
		} catch (Exception e) {
			android.os.CancellationSignal signal = recoveryCancellation; recoveryCancellation = null;
			BiometricAttempt attempt = recoveryBiometric; recoveryBiometric = null;
			if (signal != null) signal.cancel();
			if (attempt != null) attempt.close();
			running.set(false); message.setText(R.string.security_biometric_retry);
		}
	}
}
