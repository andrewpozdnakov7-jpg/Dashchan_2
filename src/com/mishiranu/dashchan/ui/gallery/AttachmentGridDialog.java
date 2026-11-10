package com.mishiranu.dashchan.ui.gallery;

import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.RequiresApi;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.PredictiveBackMotion;

/** User close may animate; forced dismissal always tears down immediately. */
public final class AttachmentGridDialog extends com.mishiranu.dashchan.content.security.SecureDialog {
	private AttachmentGridMotionController motion;
	private Object predictiveBackCallback;
	private int callbackGeneration;
	private boolean started, cancelable = true, backGestureStarted, backGestureIme, backInvocationHandled;
	private final Runnable releaseBackInvocation = () -> backInvocationHandled = false;
	private final SharedPreferences.Listener motionPreferencesListener = key -> {
		if (Preferences.KEY_PREDICTIVE_BACK.equals(key) || Preferences.KEY_NEW_INTERFACE_MOTION.equals(key)) {
			unregisterPredictiveBack(); backGestureStarted = false;
			if (motion != null) motion.finish();
			if (started) registerPredictiveBack();
		}
	};

	public AttachmentGridDialog(Context context, int theme) { super(context, theme); }

	public void setMotionContent(View content, View source) {
		unregisterPredictiveBack(); backGestureStarted = false;
		if (motion != null) motion.dispose();
		motion = new AttachmentGridMotionController(content, getWindow(), source);
		if (started) registerPredictiveBack();
	}

	@Override protected void onStart() {
		super.onStart(); started = true; backGestureStarted = backInvocationHandled = false;
		Preferences.PREFERENCES.register(motionPreferencesListener);
		registerPredictiveBack();
		if (motion != null) motion.open();
	}

	@Override public boolean dispatchTouchEvent(MotionEvent event) {
		// Back starts before Android cancels the child's existing touch stream.
		// Never swallow ACTION_CANCEL or its pending long press can survive the preview.
		if (event.getActionMasked() != MotionEvent.ACTION_CANCEL && motion != null && motion.isRunning()) return true;
		return super.dispatchTouchEvent(event);
	}

	@Override public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus) {
			unregisterPredictiveBack(); backGestureStarted = false;
			if (motion != null) motion.finish();
		} else if (started) registerPredictiveBack();
	}

	@Override public void setCancelable(boolean value) {
		super.setCancelable(value); cancelable = value;
		if (!value) { backGestureStarted = false; if (motion != null) motion.finish(); }
	}
	@Override public void setCanceledOnTouchOutside(boolean value) {
		super.setCanceledOnTouchOutside(value); if (value) cancelable = true;
	}

	private void startPredictiveBack() {
		if (motion != null && motion.isPredictiveBackCommitting()) return;
		backGestureStarted = true; backGestureIme = PredictiveBackMotion.isImeVisible(getWindow().getDecorView());
		if (cancelable && !backGestureIme && motion != null) motion.startPredictiveBack();
	}
	private void cancelPredictiveBack() {
		backGestureStarted = false;
		if (motion != null) motion.cancelPredictiveBack();
	}
	private void handleBackInvocation() {
		if (!started || !isShowing() || backInvocationHandled) return;
		backInvocationHandled = true; getWindow().getDecorView().postOnAnimation(releaseBackInvocation);
		if (motion != null && motion.isPredictiveBackCommitting()) return;
		boolean ime = PredictiveBackMotion.isImeVisible(getWindow().getDecorView());
		boolean localIme = backGestureStarted && backGestureIme; backGestureStarted = false;
		if (ime || localIme) {
			if (motion != null) motion.finish();
			if (ime) PredictiveBackMotion.hideIme(getWindow().getDecorView());
			return;
		}
		if (!cancelable) { if (motion != null) motion.finish(); return; }
		if (motion != null && motion.commitPredictiveBack(this::cancelImmediately)) return;
		performNativeBack();
	}
	@SuppressWarnings("deprecation")
	private void performNativeBack() { super.onBackPressed(); }
	@SuppressWarnings("deprecation")
	@Override public void onBackPressed() { handleBackInvocation(); }

	@Override public void cancel() {
		backGestureStarted = false;
		if (motion == null || !motion.close(this::cancelImmediately)) cancelImmediately();
	}

	private void cancelImmediately() { super.cancel(); }

	@Override public void dismiss() {
		unregisterPredictiveBack(); backGestureStarted = false;
		if (motion != null) motion.dispose();
		super.dismiss();
	}

	@Override protected void onStop() {
		started = false; backGestureStarted = false;
		Preferences.PREFERENCES.unregister(motionPreferencesListener);
		unregisterPredictiveBack(); getWindow().getDecorView().removeCallbacks(releaseBackInvocation);
		if (motion != null) motion.dispose();
		super.onStop();
	}

	private boolean currentCallback(int generation) { return started && isShowing() && generation == callbackGeneration; }
	private void registerPredictiveBack() {
		if (predictiveBackCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			int generation = ++callbackGeneration;
			predictiveBackCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && InterfaceMotion.isPredictiveBackEnabled()
					? Api34Impl.register(this, generation) : Api33Impl.register(this, generation);
		}
	}
	private void unregisterPredictiveBack() {
		callbackGeneration++;
		if (predictiveBackCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			Api33Impl.unregister(this, predictiveBackCallback); predictiveBackCallback = null;
		}
	}
	@RequiresApi(Build.VERSION_CODES.TIRAMISU)
	private static final class Api33Impl {
		static Object register(AttachmentGridDialog dialog, int generation) {
			android.window.OnBackInvokedCallback callback = () -> {
				if (dialog.currentCallback(generation)) dialog.handleBackInvocation();
			};
			dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
			return callback;
		}
		static void unregister(AttachmentGridDialog dialog, Object callback) {
			dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
		}
	}
	@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
	private static final class Api34Impl {
		static Object register(AttachmentGridDialog dialog, int generation) {
			android.window.OnBackAnimationCallback callback = new android.window.OnBackAnimationCallback() {
				@Override public void onBackStarted(android.window.BackEvent event) {
					if (dialog.currentCallback(generation)) dialog.startPredictiveBack();
				}
				@Override public void onBackProgressed(android.window.BackEvent event) {
					if (dialog.currentCallback(generation) && dialog.backGestureStarted && dialog.motion != null)
						dialog.motion.progressPredictiveBack(event.getProgress());
				}
				@Override public void onBackCancelled() {
					if (dialog.currentCallback(generation)) dialog.cancelPredictiveBack();
				}
				@Override public void onBackInvoked() {
					if (dialog.currentCallback(generation)) dialog.handleBackInvocation();
				}
			};
			dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
			return callback;
		}
	}
}
