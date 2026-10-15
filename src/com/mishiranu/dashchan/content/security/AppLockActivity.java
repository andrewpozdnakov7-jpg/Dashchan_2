package com.mishiranu.dashchan.content.security;

import android.app.AlertDialog;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
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
import com.mishiranu.dashchan.util.AndroidUtils;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PIN input is never written into Bundle, preferences, logs, autofill, or the clipboard. */
public final class AppLockActivity extends ComponentActivity {
	public static final String PRIMARY = "primary";
	public static final String ADDITIONAL = "additional";
	public static final String REMOVE_ADDITIONAL = "remove_additional";
	public static final String BIOMETRIC = "biometric";
	public static final String DISABLE = "disable";
	public static final String DOWNLOADS = "downloads";
	private enum Step { UNLOCK, AUTHORIZE, NEW, CONFIRM }
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private Step step;
	private String action;
	private EditText input;
	private TextView title, status;
	private Button submit, biometric;
	private char[] firstPin;
	private boolean busy;
	private volatile boolean resumed;
	private boolean failedClosed;
	private volatile int authorizationGeneration;
	private volatile boolean primaryAuthorized, initialSetup;
	private volatile long authorizationLease = -1;
	private CancellationSignal cancellation;
	private AlertDialog approvalDialog;
	static void configurePinInput(EditText input) {
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
		// setSingleLine may install its own transformation; password masking must win.
		input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
		input.setSaveEnabled(false); input.setFreezesText(false);
		input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		input.setFilterTouchesWhenObscured(true); input.setLongClickable(false);
		input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
	}

	@Override protected void onCreate(Bundle state) {
		super.onCreate(state);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				if (action == null) moveTaskToBack(true); else finish();
			}
		});
		LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
		int padding = Math.round(24 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, padding); layout.setGravity(Gravity.CENTER);
		title = new TextView(this); title.setTextSize(22); layout.addView(title);
		status = new TextView(this); status.setPadding(0, padding, 0, padding); layout.addView(status);
		input = new EditText(this);
		configurePinInput(input); input.setHint(R.string.security_pin_hint);
		layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
		submit = new Button(this); submit.setText(android.R.string.ok);
		submit.setFilterTouchesWhenObscured(true); submit.setOnClickListener(v -> submit()); layout.addView(submit);
		biometric = new Button(this); biometric.setText(R.string.security_biometric);
		biometric.setFilterTouchesWhenObscured(true); biometric.setOnClickListener(v -> authenticateBiometric(false));
		layout.addView(biometric);
		android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true);
		scroll.addView(layout, new android.widget.ScrollView.LayoutParams(-1, -2));
		scroll.setOnApplyWindowInsetsListener((view, insets) -> {
			android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
			view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
		});
		setContentView(scroll);
		action = getIntent().getStringExtra("security_action");
		try {
			if (SecurityStore.pending(this)) { startReset(); return; }
			authorizationLease = SecuritySessionProvider.generation(this);
			boolean enabled = SecurityStore.enabled(this);
			initialSetup = !enabled && PRIMARY.equals(action);
			if (action != null && (SecurityStore.locked(this) || !enabled && !PRIMARY.equals(action))) {
				finish(); return;
			}
			if (action == null && !SecurityStore.locked(this)) { complete(); return; }
			step = action == null ? Step.UNLOCK : enabled ? Step.AUTHORIZE : Step.NEW;
			updatePrompt();
		} catch (IOException e) { unavailable(); }
	}

	private void updatePrompt() {
		title.setText(action == null ? R.string.security_unlock : ADDITIONAL.equals(action)
				? R.string.security_additional_pin : R.string.security_title);
		status.setText(step == Step.NEW ? R.string.security_new_pin : step == Step.CONFIRM
				? R.string.security_confirm_pin : step == Step.AUTHORIZE ? R.string.security_current_pin : R.string.security_enter_pin);
		input.setText("");
		boolean showBiometric = false;
		try { showBiometric = step == Step.UNLOCK && SecurityStore.update(this, s -> s.optBoolean("biometric")); }
		catch (IOException ignored) {}
		biometric.setVisibility(showBiometric ? View.VISIBLE : View.GONE);
	}

	private void submit() {
		if (busy || step == null) return;
		char[] pin = new char[input.length()]; input.getText().getChars(0, pin.length, pin, 0); input.setText("");
		if (!PinCredential.isValid(pin)) { PinCredential.clear(pin); status.setText(R.string.security_pin_hint); return; }
		if (step == Step.NEW) {
			firstPin = pin; step = Step.CONFIRM; updatePrompt(); return;
		}
		if (step == Step.CONFIRM) {
			if (!java.util.Arrays.equals(firstPin, pin)) {
				PinCredential.clear(firstPin); firstPin = null; PinCredential.clear(pin);
				step = Step.NEW; updatePrompt(); status.setText(R.string.security_pin_mismatch); return;
			}
			PinCredential.clear(firstPin); firstPin = null;
			setBusy(true);
			int generation = authorizationGeneration;
			long session = authorizationLease;
			worker.execute(() -> {
				try {
					SecurityStore.setPin(this, pin, ADDITIONAL.equals(action), () -> settingsAuthorized(generation));
					runOnUiThread(() -> {
						if (!canAccept(generation)) return;
						try { SecurityStore.unlock(this, session + 1); complete(); } catch (IOException e) { unavailable(); }
					});
				} catch (IOException e) { runOnUiThread(() -> {
					if (!canAccept(generation)) return;
					step = Step.NEW; updatePrompt(); status.setText(R.string.security_save_failed);
				}); }
				finally { PinCredential.clear(pin); runOnUiThread(() -> setBusy(false)); }
			});
			return;
		}
		setBusy(true);
		boolean authorize = step == Step.AUTHORIZE;
		int generation = authorizationGeneration;
		long session = SecuritySessionProvider.generation(this);
		worker.execute(() -> {
			try {
				SecurityStore.Result result = SecurityStore.verify(this, pin, authorize);
				runOnUiThread(() -> {
					if (!canAccept(generation)) return;
					try {
						if (result == SecurityStore.Result.ADDITIONAL) {
							String currentTree = com.mishiranu.dashchan.content.Preferences.PREFERENCES.getString("download_uri_tree", null);
							SecurityStore.authorizedUpdate(this, () -> canAccept(generation)
									&& session >= 0 && session == SecuritySessionProvider.generation(this), s -> {
								String approved = PrivacyResetPolicy.approvedDownloadTarget(s.optString("delete_downloads_uri", null), currentTree);
								if (approved != null) s.put("reset_tree", approved);
								else s.remove("reset_tree");
								s.put("profile_generation", s.optLong("profile_generation") + 1);
								s.put("unlocked", false).put("reset_pending", true).remove("reset_local_done");
								return null;
							});
							startReset();
						} else if (result == SecurityStore.Result.PRIMARY) {
							if (authorize) { authorizationLease = session; authorized(); }
							else { SecurityStore.unlock(this, session); complete(); }
						} else status.setText(result == SecurityStore.Result.WAIT
								? R.string.security_retry_later : R.string.security_wrong_pin);
					} catch (IOException e) { unavailable(); }
				});
			} catch (IOException e) { runOnUiThread(this::unavailable); }
			finally { PinCredential.clear(pin); runOnUiThread(() -> setBusy(false)); }
		});
	}

	private void authorized() {
		if (isFinishing() || isDestroyed()) return;
		primaryAuthorized = true;
		final int generation = authorizationGeneration;
		if (PRIMARY.equals(action)) { step = Step.NEW; updatePrompt(); }
		else if (ADDITIONAL.equals(action)) {
			approvalDialog = new com.mishiranu.dashchan.content.security.SecureDialogBuilder(this).setTitle(R.string.security_additional_pin)
					.setMessage(R.string.security_delete_warning)
					.setPositiveButton(R.string.security_understand, (d, w) -> {
						if (!settingsAuthorized(generation)) return;
						step = Step.NEW; updatePrompt();
					})
					.setNegativeButton(android.R.string.cancel, (d, w) -> finish())
					.setOnCancelListener(d -> finish()).show();
		} else if (BIOMETRIC.equals(action)) {
			try {
				boolean enabled = SecurityStore.update(this, s -> s.optBoolean("biometric"));
				if (enabled) { settingsUpdate(s -> { s.put("biometric", false); return null; }); complete(); }
				else authenticateBiometric(true);
			} catch (IOException e) { unavailable(); }
		} else if (DOWNLOADS.equals(action)) {
			configureDownloads();
		} else if (DISABLE.equals(action) || REMOVE_ADDITIONAL.equals(action)) {
			try {
				settingsUpdate(s -> {
					s.remove("additional");
					s.remove("delete_downloads_uri"); s.remove("delete_downloads_label");
					if (DISABLE.equals(action)) { s.remove("primary"); s.remove("biometric"); s.put("unlocked", true); }
					return null;
				});
				complete();
			} catch (IOException e) { unavailable(); }
		} else finish();
	}

	private void configureDownloads() {
		try {
			if (!SecurityStore.update(this, s -> s.has("additional"))) {
				status.setText(R.string.security_need_additional); return;
			}
			String approved = SecurityStore.update(this, s -> s.optString("delete_downloads_uri", null));
			if (approved != null) {
				settingsUpdate(s -> { s.remove("delete_downloads_uri"); s.remove("delete_downloads_label"); return null; });
				complete(); return;
			}
			final int generation = authorizationGeneration;
			setBusy(true);
			worker.execute(() -> {
			try {
				// A removable storage provider may stall: never query it on the input thread.
				android.net.Uri tree = com.mishiranu.dashchan.content.Preferences.getDownloadUriTree(this);
				String description = DownloadTreeCleanup.describe(this, tree);
				runOnUiThread(() -> {
				if (!settingsAuthorized(generation)) return;
			approvalDialog = new com.mishiranu.dashchan.content.security.SecureDialogBuilder(this).setTitle(R.string.security_delete_downloads)
					.setMessage(getString(R.string.security_downloads_warning) + "\n\n" + description)
					.setPositiveButton(R.string.security_understand, (d, w) -> {
						if (!settingsAuthorized(generation)) return;
						try {
							settingsUpdate(s -> { s.put("delete_downloads_uri", tree.toString()); s.put("delete_downloads_label", description); return null; });
							complete();
						} catch (IOException e) { unavailable(); }
					}).setNegativeButton(android.R.string.cancel, (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
				});
			} catch (IOException | RuntimeException e) {
				runOnUiThread(() -> { if (canAccept(generation)) status.setText(R.string.security_downloads_unavailable); });
			} finally { runOnUiThread(() -> setBusy(false)); }
			});
		} catch (IOException e) { status.setText(R.string.security_downloads_unavailable); }
	}

	private void authenticateBiometric(boolean enroll) {
		if (cancellation != null || isFinishing()) return;
		BiometricManager manager = getSystemService(BiometricManager.class);
		if (manager == null || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
				!= BiometricManager.BIOMETRIC_SUCCESS) { status.setText(R.string.security_biometric_unavailable); return; }
		try {
			int generation = authorizationGeneration;
			long session = SecuritySessionProvider.generation(this);
			BiometricPrompt.CryptoObject crypto = new BiometricPrompt.CryptoObject(SecurityStore.biometricMac(enroll));
			cancellation = new CancellationSignal();
			new BiometricPrompt.Builder(this).setTitle(getString(R.string.security_unlock))
					.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
					.setNegativeButton(getString(android.R.string.cancel), getMainExecutor(), (d, w) -> {})
					.build().authenticate(crypto, cancellation, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
						@Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
							cancellation = null;
							if (!canAccept(generation)) return;
							try {
								if (result.getCryptoObject() == null || result.getCryptoObject().getMac() == null) throw new IOException();
								result.getCryptoObject().getMac().doFinal(new byte[]{1});
								if (enroll) settingsUpdate(s -> { s.put("biometric", true); return null; });
								SecurityStore.unlock(AppLockActivity.this, session); complete();
							} catch (Exception e) { status.setText(R.string.security_biometric_unavailable); }
						}
						@Override public void onAuthenticationError(int code, CharSequence message) {
							cancellation = null; status.setText(R.string.security_biometric_unavailable);
						}
					});
		} catch (Exception e) { cancellation = null; status.setText(R.string.security_biometric_unavailable); }
	}

	private void complete() {
		if (isFinishing() || isDestroyed()) return;
		AppSecurity.refresh();
		Intent resume = AndroidUtils.getParcelableExtra(getIntent(), "resume", Intent.class);
		if (resume != null && resume.getComponent() != null
				&& getPackageName().equals(resume.getComponent().getPackageName())) startActivity(resume);
		finish();
	}

	private void startReset() {
		if (!isDestroyed()) startActivity(new Intent(this, PrivacyResetActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
	}

	private void unavailable() {
		failedClosed = true;
		status.setText(R.string.security_unavailable); submit.setEnabled(false); biometric.setVisibility(View.GONE);
	}

	private void setBusy(boolean value) {
		busy = value;
		if (!isDestroyed()) { submit.setEnabled(!value && !failedClosed); input.setEnabled(!value && !failedClosed); biometric.setEnabled(!value && !failedClosed); }
	}

	private boolean canAccept(int generation) {
		return resumed && generation == authorizationGeneration && !isFinishing() && !isDestroyed()
				&& getSystemService(android.os.PowerManager.class).isInteractive();
	}

	private boolean settingsAuthorized(int generation) {
		return canAccept(generation) && authorizationLease >= 0
				&& authorizationLease == SecuritySessionProvider.generation(this)
				&& (initialSetup || primaryAuthorized && SecuritySessionProvider.isUnlocked(this));
	}

	private <T> T settingsUpdate(SecurityStore.Change<T> change) throws IOException {
		int generation = authorizationGeneration;
		return SecurityStore.authorizedUpdate(this, () -> settingsAuthorized(generation), change);
	}

	@Override protected void onResume() {
		super.onResume(); resumed = true;
		if (action != null && !initialSetup && (!primaryAuthorized || SecurityStore.locked(this)) && step != null) {
			PinCredential.clear(firstPin); firstPin = null;
			step = Step.AUTHORIZE; updatePrompt();
		}
		else if (step == Step.NEW) updatePrompt();
	}

	@Override protected void onPause() {
		resumed = false; primaryAuthorized = false; authorizationGeneration++;
		if (approvalDialog != null) approvalDialog.dismiss(); approvalDialog = null;
		if (cancellation != null) cancellation.cancel(); cancellation = null;
		super.onPause();
	}

	@Override protected void onStop() {
		if (approvalDialog != null) approvalDialog.dismiss(); approvalDialog = null;
		if (cancellation != null) cancellation.cancel(); cancellation = null;
		input.setText("");
		PinCredential.clear(firstPin); firstPin = null;
		if (step == Step.CONFIRM) step = Step.NEW;
		super.onStop();
	}

	@Override protected void onDestroy() {
		PinCredential.clear(firstPin); firstPin = null;
		worker.shutdownNow(); super.onDestroy();
	}
}
