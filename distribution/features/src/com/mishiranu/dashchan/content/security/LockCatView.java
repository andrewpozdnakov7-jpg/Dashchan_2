package com.mishiranu.dashchan.content.security;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;

/** Receives geometry only, never digits or credential text. One shared 512px canvas. */
final class LockCatView extends FrameLayout {
	private final ImageView glasses;
	private ValueAnimator animator;
	private final Runnable idle = () -> neutral(180);
	LockCatView(Context context) {
		super(context);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		setFocusable(false); setClickable(false); setClipChildren(false); setClipToPadding(false);
		ImageView head = new ImageView(context); head.setImageResource(R.drawable.pin_cat_head);
		glasses = new ImageView(context); glasses.setImageResource(R.drawable.pin_cat_glasses);
		head.setScaleType(ImageView.ScaleType.FIT_CENTER); glasses.setScaleType(ImageView.ScaleType.FIT_CENTER);
		addView(head, new LayoutParams(-1, -1)); addView(glasses, new LayoutParams(-1, -1));
	}
	@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		setPivotX(w * .50f); setPivotY(h * .72f);
		glasses.setPivotX(w * .51f); glasses.setPivotY(h * .35f);
		setCameraDistance(1200 * getResources().getDisplayMetrics().density);
	}
	static boolean motionEnabled() { return InterfaceMotion.isEnabled() && ValueAnimator.areAnimatorsEnabled(); }
	private void stop() {
		removeCallbacks(idle);
		ValueAnimator old = animator; animator = null;
		if (old != null) { old.removeAllListeners(); old.cancel(); }
	}
	void follow(float nx, float ny) {
		if (!motionEnabled()) { reset(); return; }
		nx = Math.max(-1, Math.min(1, nx)); ny = Math.max(0, Math.min(1, ny));
		move(4 * nx, 2 + 4 * ny, getWidth() * (3f / 96) * nx, 0, nx, 0, 140);
		postDelayed(idle, 600);
	}
	void neutral(int duration) {
		if (!motionEnabled()) { reset(); return; }
		move(0, 0, 0, 0, 0, 0, duration);
	}
	void requestStarted() {
		if (motionEnabled()) move(0, 0, 0, -getWidth() * (2f / 140), 0, 0, 100);
	}
	private void move(float rz, float rx, float tx, float ty, float gz, float gy, int duration) {
		stop();
		float startZ = getRotation(), startX = getRotationX(), startTX = getTranslationX(), startTY = getTranslationY();
		float startGZ = glasses.getRotation(), startGY = glasses.getTranslationY();
		ValueAnimator next = ValueAnimator.ofFloat(0, 1); animator = next;
		next.setDuration(duration); next.setInterpolator(InterfaceMotion.STANDARD);
		next.addUpdateListener(a -> {
			float t = (float) a.getAnimatedValue();
			setRotation(lerp(startZ, rz, t)); setRotationX(lerp(startX, rx, t));
			setTranslationX(lerp(startTX, tx, t)); setTranslationY(lerp(startTY, ty, t));
			glasses.setRotation(lerp(startGZ, gz, t)); glasses.setTranslationY(lerp(startGY, gy, t));
		});
		next.start();
	}
	int success(boolean bio, View exiting, Runnable complete) {
		stop();
		if (!motionEnabled() || !isAttachedToWindow()) { reset(); complete.run(); return 0; }
		int total = bio ? 450 : 400;
		float startZ = getRotation(), startX = getRotationX(), startTX = getTranslationX(), startTY = getTranslationY();
		float startGY = glasses.getTranslationY(), startGZ = glasses.getRotation();
		float down = getWidth() * (bio ? 10f / 140 : 8f / 96);
		ValueAnimator next = ValueAnimator.ofFloat(0, total); animator = next;
		next.setDuration(total); next.setInterpolator(new android.view.animation.LinearInterpolator());
		next.addUpdateListener(a -> {
			float ms = (float) a.getAnimatedValue();
			float face = phase(ms, 0, 100);
			float nod = phase(ms, bio ? 150 : 180, bio ? 225 : 290)
					* (1 - phase(ms, bio ? 225 : 290, bio ? 300 : total));
			setRotation(lerp(startZ, 0, face)); setRotationX(lerp(startX, 0, face) + (bio ? 4 : 6) * nod);
			setTranslationX(lerp(startTX, 0, face));
			setTranslationY(lerp(startTY, 0, face) + getWidth() * (bio ? 1.5f / 140 : 2f / 96) * nod);
			glasses.setRotation(lerp(startGZ, 0, face));
			glasses.setTranslationY(lerp(startGY, down, phase(ms, bio ? 0 : 80, bio ? 150 : 240)));
			if (exiting != null) exiting.setAlpha(1 - phase(ms, 300, total));
		});
		next.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator animation) {
				if (animator == animation) { animator = null; complete.run(); }
			}
		});
		next.start(); return scaledDeadline(getContext(), total);
	}
	static int scaledDeadline(Context context, int duration) {
		float scale;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) scale = ValueAnimator.getDurationScale();
		else {
			try { scale = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1); }
			catch (SecurityException e) { scale = 1; }
		}
		return Math.max(1, Math.round(duration * (Float.isFinite(scale) && scale > 0 ? scale : 1)));
	}
	private static float phase(float ms, float from, float to) {
		return InterfaceMotion.STANDARD.getInterpolation(Math.max(0, Math.min(1, (ms - from) / (to - from))));
	}
	private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
	void reset() {
		stop(); setRotation(0); setRotationX(0); setTranslationX(0); setTranslationY(0);
		setScaleX(1); setScaleY(1); setAlpha(1);
		glasses.setTranslationX(0); glasses.setTranslationY(0); glasses.setRotation(0);
	}
	void cancel() { stop(); }
	@Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
}
