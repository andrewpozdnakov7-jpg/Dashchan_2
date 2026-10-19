package com.mishiranu.dashchan.content.security;

import android.app.AlertDialog;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
	public static final String SCREENSHOTS = "screenshots";
	public static final String REAUTHENTICATION = "reauthentication";
	public static final String AUTHENTICATION_MODE = "authentication_mode";
	private enum Step { UNLOCK, AUTHORIZE, CHOOSE_MODE, NEW, CONFIRM }
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private Step step;
	private String action;
	private EditText input;
	private TextView title, status;
	private Button submit, biometric, additional;
	private LockScreenForm form;
	private boolean pinPresentation, presentationFinishing, decoratedExit;
	private LockPresentationCompletion presentationCompletion;
	private Runnable presentationFallback;
	private AuthenticationPolicy.Mode pendingMode;
	private org.json.JSONObject pendingPrimary;
	private BiometricAttempt biometricAttempt;
	private boolean autoBiometricShown, additionalEntry;
	private char[] firstPin;
	private boolean busy;
	private volatile boolean resumed;
	private boolean failedClosed;
	private volatile int authorizationGeneration;
	private volatile boolean credentialAuthorized, initialSetup;
	private volatile long authorizationLease = -1;
	private long authenticatedAt = -1;
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
		AppSecurity.refreshCaptureWindow(this);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				if (action == null) moveTaskToBack(true); else finish();
			}
		});
		form = new LockScreenDesign(this);
		input = form.input; title = form.title; status = form.status;
		submit = form.submit; biometric = form.biometric; additional = form.additional;
		submit.setOnClickListener(v -> submit());
		biometric.setText(R.string.security_biometric);
		biometric.setOnClickListener(v -> {
			if (busy || presentationFinishing || failedClosed) return;
			if (step == Step.UNLOCK && pinPresentation && !additionalEntry) {
				pinPresentation = false; updatePrompt();
			}
			authenticateBiometric(enrollmentReady());
		});
		additional.setText(R.string.security_additional_pin_entry);
		additional.setOnClickListener(v -> {
			if (busy || presentationFinishing || failedClosed) return;
			cancelBiometric(); additionalEntry = true; pinPresentation = true; updatePrompt();
		});
		form.usePin.setOnClickListener(v -> {
			if (busy || presentationFinishing || failedClosed) return;
			try {
				AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(this);
				if (step != Step.UNLOCK || mode == null || !mode.pin) return;
				cancelBiometric(); autoBiometricShown = true; pinPresentation = true; updatePrompt();
			} catch (IOException e) { unavailable(); }
		});
		form.inputChanged = this::refreshControls;
		form.root.setOnApplyWindowInsetsListener((view, insets) -> {
			android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
					| android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
			view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
		});
		setContentView(form.root);
		action = getIntent().getStringExtra("security_action");
		try {
			if (SecurityStore.pending(this)) { startReset(); return; }
			authorizationLease = SecuritySessionProvider.generation(this);
			boolean enabled = SecurityStore.enabled(this);
			initialSetup = !enabled && (PRIMARY.equals(action) || AUTHENTICATION_MODE.equals(action));
			if (action != null && (SecurityStore.locked(this) || !enabled && !initialSetup)) {
				finish(); return;
			}
			if (action == null && !SecurityStore.locked(this)) { complete(); return; }
			step = action == null ? Step.UNLOCK : enabled ? Step.AUTHORIZE
					: AUTHENTICATION_MODE.equals(action) ? Step.CHOOSE_MODE : Step.NEW;
			updatePrompt();
		} catch (IOException e) { unavailable(); }
	}

	private void updatePrompt() {
		if (failedClosed) return;
		AuthenticationPolicy.Mode mode;
		boolean hasAdditional;
		try {
			mode = SecurityStore.authenticationMode(this);
			hasAdditional = SecurityStore.update(this, s -> s.has("additional"));
		} catch (IOException e) { unavailable(); return; }
		boolean bioOnly = mode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY;
		int prompt;
		if (step == Step.NEW) prompt = R.string.security_new_pin;
		else if (step == Step.CONFIRM) prompt = R.string.security_confirm_pin;
		else if (step == Step.CHOOSE_MODE) prompt = enrollmentReady()
				? R.string.security_confirm_biometric : R.string.security_authentication_mode;
		else if (bioOnly && !additionalEntry) prompt = R.string.security_confirm_biometric;
		else if (step == Step.AUTHORIZE) prompt = R.string.security_current_pin;
		else prompt = additionalEntry ? R.string.security_additional_pin_entry : R.string.security_enter_pin;
		input.setText("");
		boolean welcome = step == Step.UNLOCK && mode != null && mode.biometric && !additionalEntry
				&& !pinPresentation && (biometricAvailable() || bioOnly);
		boolean showPin = step == Step.NEW || step == Step.CONFIRM
				|| step == Step.UNLOCK && !welcome && (!bioOnly || additionalEntry) || step == Step.AUTHORIZE && !bioOnly;
		boolean showBiometric = enrollmentReady() || mode != null && mode.biometric
				&& (bioOnly || biometricAvailable())
				&& (step == Step.UNLOCK || step == Step.AUTHORIZE && bioOnly);
		int heading = step == Step.NEW && ADDITIONAL.equals(action) ? R.string.security_additional_pin : prompt;
		int description = step == Step.UNLOCK ? R.string.security_unlock : step == Step.AUTHORIZE
				? R.string.security_current_pin : prompt;
		int button = step == Step.UNLOCK ? R.string.security_unlock : android.R.string.ok;
		form.render(heading, description, button, showPin, showBiometric,
				step == Step.UNLOCK && bioOnly && hasAdditional && !additionalEntry, welcome, mode != null && mode.pin);
		if (bioOnly && !biometricAvailable() && !showPin) status.setText(R.string.security_biometric_retry);
		refreshControls();
	}
	private boolean biometricAvailable() {
		BiometricManager manager = getSystemService(BiometricManager.class);
		return manager != null && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
				== BiometricManager.BIOMETRIC_SUCCESS;
	}
	private boolean enrollmentReady() {
		return pendingMode != null && pendingMode.biometric && step == Step.CHOOSE_MODE;
	}

	private void submit() {
		if (failedClosed || busy || presentationFinishing || form.transitioning() || cancellation != null
				|| step == null || input.getVisibility() != View.VISIBLE) return;
		char[] pin = new char[input.length()]; input.getText().getChars(0, pin.length, pin, 0); input.setText("");
		if (!PinCredential.isValid(pin)) { PinCredential.clear(pin); status.setText(R.string.security_pin_hint); form.error(); return; }
		if (step == Step.NEW) {
			firstPin = pin; step = Step.CONFIRM; updatePrompt(); return;
		}
		if (step == Step.CONFIRM) {
			if (!java.util.Arrays.equals(firstPin, pin)) {
				PinCredential.clear(firstPin); firstPin = null; PinCredential.clear(pin);
				step = Step.NEW; updatePrompt(); status.setText(R.string.security_pin_mismatch); form.error(); return;
			}
			PinCredential.clear(firstPin); firstPin = null;
			setBusy(true);
			int generation = authorizationGeneration;
			long session = authorizationLease;
			worker.execute(() -> {
				try {
					if (pendingMode != null) {
						org.json.JSONObject prepared = SecurityStore.preparePrimaryCredential(this, pin, () -> settingsAuthorized(generation));
						runOnUiThread(() -> {
							if (!settingsAuthorized(generation)) return;
							pendingPrimary = prepared;
							if (pendingMode.biometric) {
								step = Step.CHOOSE_MODE; setBusy(false); updatePrompt(); authenticateBiometric(true);
							} else commitMode(null);
						});
						return;
					}
					SecurityStore.setPin(this, pin, ADDITIONAL.equals(action), () -> settingsAuthorized(generation));
					runOnUiThread(() -> {
						if (!canAccept(generation)) return;
						try { SecurityStore.authenticatedUnlock(this, session + 1); presentSuccess(false, true, this::complete); } catch (IOException e) { unavailable(); }
					});
				} catch (IOException e) { runOnUiThread(() -> {
					if (!canAccept(generation)) return;
					step = Step.NEW; updatePrompt(); status.setText(R.string.security_save_failed); form.error();
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
							authenticatedAt = android.os.SystemClock.elapsedRealtime();
							if (authorize) {
								// Verification alone does not reopen the RAM session after Home/screen-off.
								// Grant only the exact lease captured before this fresh PRIMARY verification.
								SecurityStore.restoreSettingsSession(this, session);
								authorizationLease = session; presentSuccess(false, false, this::authorized);
							}
							else { SecurityStore.authenticatedUnlock(this, session); presentSuccess(false, true, this::complete); }
						} else {
							status.setText(result == SecurityStore.Result.WAIT ? R.string.security_retry_later : R.string.security_wrong_pin);
							form.error();
						}
					} catch (IOException e) { unavailable(); }
				});
			} catch (IOException e) { runOnUiThread(this::unavailable); }
			finally { PinCredential.clear(pin); runOnUiThread(() -> setBusy(false)); }
		});
	}

	private void authorized() {
		if (isFinishing() || isDestroyed()) return;
		credentialAuthorized = true;
		final int generation = authorizationGeneration;
		if (AUTHENTICATION_MODE.equals(action)) { chooseMode(); }
		else if (PRIMARY.equals(action)) {
			try {
				if (SecurityStore.authenticationMode(this) == AuthenticationPolicy.Mode.BIOMETRIC_ONLY) chooseMode();
				else { step = Step.NEW; updatePrompt(); }
			} catch (IOException e) { unavailable(); }
		}
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
			chooseMode(); // Compatibility for an older restored settings destination.
		} else if (DOWNLOADS.equals(action)) {
			configureDownloads();
		} else if (SCREENSHOTS.equals(action)) {
			configureScreenshots();
		} else if (REAUTHENTICATION.equals(action)) {
			configureReauthentication();
		} else if (DISABLE.equals(action) || REMOVE_ADDITIONAL.equals(action)) {
			try {
				settingsUpdate(s -> {
					s.remove("additional");
					s.remove("delete_downloads_uri"); s.remove("delete_downloads_label");
					if (DISABLE.equals(action)) {
						s.remove("primary"); s.remove("biometric"); s.remove("biometric_alias");
						s.remove(AuthenticationPolicy.KEY); s.remove(SecurityStore.ALLOW_SCREENSHOTS);
						s.put("unlocked", true);
					}
					return null;
				});
				complete();
			} catch (IOException e) { unavailable(); }
		} else finish();
	}
	public static int modeTitle(AuthenticationPolicy.Mode mode) {
		return mode == AuthenticationPolicy.Mode.PIN_ONLY ? R.string.security_mode_pin
				: mode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY ? R.string.security_mode_biometric
				: mode == AuthenticationPolicy.Mode.PIN_OR_BIOMETRIC ? R.string.security_mode_pin_biometric
				: R.string.security_disabled;
	}
	private void chooseMode() {
		try {
			AuthenticationPolicy.Mode current = SecurityStore.authenticationMode(this);
			AuthenticationPolicy.Mode[] modes = AuthenticationPolicy.Mode.values();
			String[] labels = new String[modes.length];
			for (int i = 0; i < modes.length; i++) labels[i] = getString(modeTitle(modes[i]));
			final int generation = authorizationGeneration;
			approvalDialog = new SecureDialogBuilder(this).setTitle(R.string.security_authentication_mode)
					.setSingleChoiceItems(labels, current != null ? current.ordinal() : -1, (dialog, which) -> {
						if (!settingsAuthorized(generation) || which < 0 || which >= modes.length) return;
						dialog.dismiss(); approvalDialog = null;
						if (modes[which] == current) { complete(); return; }
						pendingMode = modes[which]; pendingPrimary = null;
						if (pendingMode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY) {
							approvalDialog = new SecureDialogBuilder(this).setTitle(R.string.security_mode_biometric)
									.setMessage(R.string.security_biometric_only_warning)
									.setPositiveButton(R.string.security_understand, (d, w) -> {
										if (settingsAuthorized(generation)) beginModeSetup();
									}).setNegativeButton(android.R.string.cancel, (d, w) -> finish())
									.setOnCancelListener(d -> finish()).show();
						} else beginModeSetup();
					}).setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
					.setOnCancelListener(dialog -> finish()).show();
		} catch (IOException e) { unavailable(); }
	}
	private void beginModeSetup() {
		try {
			if (pendingMode.pin && !SecurityStore.update(this, s -> s.has("primary"))) {
				step = Step.NEW; updatePrompt();
			} else if (pendingMode.biometric) {
				step = Step.CHOOSE_MODE; updatePrompt(); authenticateBiometric(true);
			} else commitMode(null);
		} catch (IOException e) { unavailable(); }
	}
	private void commitMode(BiometricAttempt proof) {
		int generation = authorizationGeneration;
		try {
			long lease = authorizationLease;
			SecurityStore.commitAuthenticationMode(this, pendingMode, pendingPrimary, proof, () -> settingsAuthorized(generation));
			if (!canAccept(generation)) return;
			SecurityStore.authenticatedUnlock(this, lease + 1,
					authenticatedAt >= 0 ? authenticatedAt : android.os.SystemClock.elapsedRealtime());
			pendingPrimary = null; pendingMode = null; presentSuccess(false, true, this::complete);
		} catch (IOException e) { unavailable(); }
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
							settingsUpdate(s -> { SecurityStateCodec.approveDownloadTarget(s, tree.toString(), description); return null; });
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

	private void configureScreenshots() {
		final int generation = authorizationGeneration;
		try {
			boolean allowed = SecurityStore.update(this, s -> s.optBoolean(SecurityStore.ALLOW_SCREENSHOTS, false));
			if (allowed) { saveScreenshots(false, generation); return; }
			approvalDialog = new SecureDialogBuilder(this).setTitle(R.string.security_allow_screenshots)
					.setMessage(ScreenCapturePolicy.designRelease(com.mishiranu.dashchan.BuildConfig.VERSION_NAME)
							? R.string.security_screenshots_design_warning : R.string.security_screenshots_warning)
					.setPositiveButton(R.string.security_understand, (d, w) -> saveScreenshots(true, generation))
					.setNegativeButton(android.R.string.cancel, (d, w) -> finish())
					.setOnCancelListener(d -> finish()).show();
		} catch (IOException e) { unavailable(); }
	}
	private void configureReauthentication() {
		final int generation = authorizationGeneration;
		try {
			int current = SecurityStore.update(this, s -> ReauthenticationPolicy.normalize(
					s.optInt(ReauthenticationPolicy.KEY, 0)));
			int[] minutes = ReauthenticationPolicy.choices();
			String[] labels = new String[minutes.length];
			for (int i = 0; i < minutes.length; i++) labels[i] = minutes[i] == 0
					? getString(R.string.security_reauthenticate_immediately)
					: getString(R.string.security_reauthenticate_minutes, minutes[i]);
			approvalDialog = new SecureDialogBuilder(this).setTitle(R.string.security_reauthentication)
					.setSingleChoiceItems(labels, ReauthenticationPolicy.indexOf(current), (dialog, which) -> {
						if (!settingsAuthorized(generation) || which < 0 || which >= minutes.length) return;
						try {
							if (minutes[which] != current) {
								settingsUpdate(s -> {
									if (SecurityStore.authenticationMode(s) == null || s.optBoolean("reset_pending")) throw new IOException("Interval unavailable");
									s.put(ReauthenticationPolicy.KEY, minutes[which]); return null;
								});
								// Changing the interval revoked the previous grant. Only this fresh
								// PRIMARY verification can create its replacement, at the original time.
								if (!canAccept(generation)) return;
								SecurityStore.authenticatedUnlock(this, authorizationLease + 1, authenticatedAt);
							}
							complete();
						} catch (IOException e) { unavailable(); }
					}).setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
					.setOnCancelListener(dialog -> finish()).show();
		} catch (IOException e) { unavailable(); }
	}

	private void saveScreenshots(boolean allowed, int generation) {
		if (!settingsAuthorized(generation)) return;
		try {
			SecurityStore.setScreenshotsAllowed(this, allowed, () -> settingsAuthorized(generation));
			complete();
		} catch (IOException e) { unavailable(); }
	}

	private void authenticateBiometric(boolean enroll) {
		if (failedClosed || busy || presentationFinishing || cancellation != null || isFinishing()) return;
		BiometricManager manager = getSystemService(BiometricManager.class);
		if (manager == null || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
				!= BiometricManager.BIOMETRIC_SUCCESS) { status.setText(R.string.security_biometric_retry); return; }
		try {
			int generation = authorizationGeneration;
			long session = SecuritySessionProvider.generation(this);
			AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(this);
			if (enroll ? !enrollmentReady() || !settingsAuthorized(generation)
					: mode == null || !mode.biometric || !(step == Step.UNLOCK
					|| step == Step.AUTHORIZE && mode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY)) return;
			final BiometricAttempt attempt = enroll ? BiometricAttempt.create(this) : BiometricAttempt.current(this);
			final CancellationSignal signal = new CancellationSignal();
			biometricAttempt = attempt; cancellation = signal;
			form.biometricStarted(); refreshControls();
			new BiometricPrompt.Builder(this).setTitle(getString(R.string.security_unlock))
					.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
					.setNegativeButton(getString(android.R.string.cancel), getMainExecutor(), (d, w) -> {
						if (biometricAttempt != attempt || cancellation != signal) return;
						cancelBiometric();
						if (canAccept(generation)) { status.setText(""); form.verifying(); }
					})
					.build().authenticate(new BiometricPrompt.CryptoObject(attempt.mac), signal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
						@Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
							if (biometricAttempt != attempt || cancellation != signal) { attempt.close(); return; }
							cancellation = null; biometricAttempt = null;
							try {
								if (!canAccept(generation)) return;
								attempt.verify(result.getCryptoObject());
								authenticatedAt = android.os.SystemClock.elapsedRealtime();
								if (enroll) { commitMode(attempt); return; }
								SecurityStore.authorizedUpdate(AppLockActivity.this, () -> canAccept(generation)
										&& session >= 0 && session == SecuritySessionProvider.generation(AppLockActivity.this), s -> {
									if (SecurityStore.authenticationMode(s) != mode || !attempt.matchesCurrent(AppLockActivity.this, s)
											|| s.optBoolean("reset_pending")) throw new IOException("Biometric authorization expired");
									return null;
								});
								if (step == Step.AUTHORIZE && mode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY) {
									SecurityStore.restoreSettingsSession(AppLockActivity.this, session);
									authorizationLease = session; presentSuccess(false, false, AppLockActivity.this::authorized);
								} else { SecurityStore.authenticatedUnlock(AppLockActivity.this, session); presentSuccess(true, true, AppLockActivity.this::complete); }
							} catch (Exception e) { if (canAccept(generation)) status.setText(R.string.security_biometric_retry); }
							finally { attempt.close(); refreshControls(); }
						}
						@Override public void onAuthenticationError(int code, CharSequence message) {
							if (biometricAttempt != attempt || cancellation != signal) { attempt.close(); return; }
							cancellation = null; biometricAttempt = null; attempt.close();
							if (canAccept(generation)) {
								boolean cancelled = code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
										|| code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED;
								status.setText(cancelled ? "" : getString(R.string.security_biometric_retry));
								form.verifying(); refreshControls();
							}
						}
					});
		} catch (Exception e) { cancelBiometric(); status.setText(R.string.security_biometric_retry); refreshControls(); }
	}
	private void cancelBiometric() {
		CancellationSignal signal = cancellation; cancellation = null;
		BiometricAttempt attempt = biometricAttempt; biometricAttempt = null;
		if (signal != null) signal.cancel();
		if (attempt != null) attempt.close();
		refreshControls();
	}

	/** Authorization has already succeeded. A cancelled decoration cannot authorize or navigate. */
	private void presentSuccess(boolean bio, boolean leaving, Runnable next) {
		if (ADDITIONAL.equals(action)) { next.run(); return; }
		cancelPresentation();
		final int generation = authorizationGeneration;
		final long lease = SecuritySessionProvider.generation(this);
		LockPresentationCompletion completion = new LockPresentationCompletion(() -> {
			presentationFinishing = false; presentationCompletion = null;
			if (presentationFallback != null) form.root.removeCallbacks(presentationFallback);
			presentationFallback = null;
			decoratedExit = leaving && form.customExit();
			next.run(); refreshControls();
		});
		presentationCompletion = completion; presentationFinishing = true; refreshControls();
		Runnable finish = () -> completion.finish(() -> {
			boolean current = canAccept(generation) && lease >= 0
					&& lease == SecuritySessionProvider.generation(this) && !SecurityStore.locked(this);
			if (!current && canAccept(generation)) unavailable();
			return current;
		});
		int duration = form.success(bio, leaving, finish);
		if (presentationCompletion == completion && duration > 0) {
			presentationFallback = finish; form.root.postDelayed(finish, duration);
		}
	}
	private void cancelPresentation() {
		if (presentationCompletion != null) presentationCompletion.cancel();
		presentationCompletion = null; presentationFinishing = false;
		if (form != null) {
			if (presentationFallback != null) form.root.removeCallbacks(presentationFallback);
			form.dispose();
		}
		presentationFallback = null;
	}

	private void complete() {
		if (isFinishing() || isDestroyed()) return;
		AppSecurity.refresh();
		Intent resume = AndroidUtils.getParcelableExtra(getIntent(), "resume", Intent.class);
		boolean ownResume = resume != null && resume.getComponent() != null
				&& getPackageName().equals(resume.getComponent().getPackageName());
		if (form != null) form.finish(this, ownResume ? resume : null, decoratedExit);
		else finish();
	}

	private void startReset() {
		AppSecurity.refreshCaptureWindow(this);
		if (!isDestroyed()) startActivity(new Intent(this, PrivacyResetActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
	}

	private void unavailable() {
		failedClosed = true;
		cancelPresentation(); cancelBiometric();
		AppSecurity.refreshCaptureWindow(this);
		status.setText(R.string.security_unavailable); submit.setEnabled(false); input.setEnabled(false);
		form.error();
		additional.setEnabled(false); biometric.setVisibility(View.GONE);
		refreshControls();
	}

	private void setBusy(boolean value) {
		busy = value;
		if (value && form != null) form.verifying();
		refreshControls();
	}
	private void refreshControls() {
		if (form != null && !isDestroyed()) form.available(resumed && !busy && !failedClosed
				&& !presentationFinishing && cancellation == null && !isFinishing());
	}

	private boolean canAccept(int generation) {
		return !failedClosed && resumed && generation == authorizationGeneration && !isFinishing() && !isDestroyed()
				&& getSystemService(android.os.PowerManager.class).isInteractive();
	}

	private boolean settingsAuthorized(int generation) {
		return canAccept(generation) && authorizationLease >= 0
				&& authorizationLease == SecuritySessionProvider.generation(this)
				&& (initialSetup || credentialAuthorized && SecuritySessionProvider.isUnlocked(this));
	}

	private <T> T settingsUpdate(SecurityStore.Change<T> change) throws IOException {
		int generation = authorizationGeneration;
		return SecurityStore.authorizedUpdate(this, () -> settingsAuthorized(generation), change);
	}

	@Override protected void onResume() {
		super.onResume(); resumed = true; decoratedExit = false;
		if (initialSetup) {
			try {
				if (SecurityStore.enabled(this)) { initialSetup = false; credentialAuthorized = false; }
				else authorizationLease = SecuritySessionProvider.generation(this);
			} catch (IOException e) { unavailable(); return; }
		}
		if (action == null && !failedClosed && !SecurityStore.locked(this)) { complete(); return; }
		if (action != null && !initialSetup && (!credentialAuthorized || SecurityStore.locked(this)) && step != null) {
			PinCredential.clear(firstPin); firstPin = null;
			step = Step.AUTHORIZE; updatePrompt();
		}
		else if (step == Step.NEW || step == Step.UNLOCK) updatePrompt();
		if (initialSetup && AUTHENTICATION_MODE.equals(action) && pendingMode == null && approvalDialog == null) {
			step = Step.CHOOSE_MODE; updatePrompt(); chooseMode();
		}
		if (!autoBiometricShown && pendingMode == null && (step == Step.UNLOCK || step == Step.AUTHORIZE)) {
			try {
				AuthenticationPolicy.Mode mode = SecurityStore.authenticationMode(this);
				if (mode != null && mode.biometric && (step == Step.UNLOCK || mode == AuthenticationPolicy.Mode.BIOMETRIC_ONLY)) {
					autoBiometricShown = true;
					final int generation = authorizationGeneration;
					biometric.post(() -> { if (canAccept(generation)) authenticateBiometric(false); });
				}
			} catch (IOException e) { unavailable(); }
		}
		AppSecurity.refreshCaptureWindow(this);
		refreshControls();
	}

	@Override protected void onPause() {
		resumed = false; credentialAuthorized = false; authenticatedAt = -1; authorizationGeneration++;
		cancelPresentation();
		autoBiometricShown = false; additionalEntry = false; pinPresentation = false; pendingMode = null; pendingPrimary = null;
		PinCredential.clear(firstPin); firstPin = null;
		if (step == Step.CONFIRM) step = Step.NEW;
		input.setText("");
		AppSecurity.refreshCaptureWindow(this);
		if (approvalDialog != null) approvalDialog.dismiss(); approvalDialog = null;
		cancelBiometric();
		super.onPause();
	}

	@Override protected void onStop() {
		cancelPresentation();
		if (approvalDialog != null) approvalDialog.dismiss(); approvalDialog = null;
		cancelBiometric();
		input.setText("");
		PinCredential.clear(firstPin); firstPin = null;
		if (step == Step.CONFIRM) step = Step.NEW;
		super.onStop();
	}

	@Override protected void onDestroy() {
		cancelPresentation();
		cancelBiometric(); pendingPrimary = null; pendingMode = null;
		PinCredential.clear(firstPin); firstPin = null;
		worker.shutdownNow(); super.onDestroy();
	}
}
