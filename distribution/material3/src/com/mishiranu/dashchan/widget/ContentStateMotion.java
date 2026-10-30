package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.function.BooleanSupplier;

/** Immediate state changes, then a bounded outgoing image and a live incoming state. */
public final class ContentStateMotion {
	private ContentStateMotion() {}
	public static boolean isActive(View view) { return view != null && view.getTag(R.id.content_state_motion_owner) instanceof Session; }
	public static void finish(View view) {
		Object tag = view != null ? view.getTag(R.id.content_state_motion_owner) : null;
		if (tag instanceof Session) ((Session) tag).finish();
	}
	public static Session prepare(ViewGroup host, View outgoing, View incoming) {
		if (outgoing == incoming) return null;
		finish(host); finish(incoming);
		if (host == null || outgoing == null || incoming == null || !InterfaceMotion.isEnabled()
				|| InterfaceMotion.duration(1) == 0 || !host.isAttachedToWindow() || !host.isShown()
				|| ElementMotion.busy(host) || ElementMotion.busy(outgoing) || ElementMotion.busy(incoming)
				|| !outgoing.isShown() || outgoing.getWidth() <= 0 || outgoing.getHeight() <= 0) return null;
		Bitmap bitmap = capture(outgoing);
		if (bitmap == null) return null;
		int[] a = new int[2], b = new int[2]; host.getLocationInWindow(a); outgoing.getLocationInWindow(b);
		Rect bounds = new Rect(b[0] - a[0], b[1] - a[1], b[0] - a[0] + outgoing.getWidth(), b[1] - a[1] + outgoing.getHeight());
		return new Session(host, incoming, bitmap, bounds);
	}
	static Bitmap capture(View view) {
		int w = view.getWidth(), h = view.getHeight(); if (w <= 0 || h <= 0) return null;
		double scale = Math.min(1d, Math.sqrt(400000d / ((double) w * h))); Bitmap bitmap = null;
		try {
			bitmap = Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap); canvas.scale(bitmap.getWidth() / (float) w, bitmap.getHeight() / (float) h);
			view.draw(canvas); return bitmap;
		} catch (RuntimeException | OutOfMemoryError error) { if (bitmap != null) bitmap.recycle(); return null; }
	}
	public static OneShotPreDrawListener revealBeforeDraw(View view, View root, BooleanSupplier valid) {
		return OneShotPreDrawListener.add(view, () -> {
			if (valid.getAsBoolean() && root != null && !isActive(view) && !isActive(root)
					&& !ElementMotion.busy(view) && !ElementMotion.busy(root)) reveal(view);
		});
	}
	public static void reveal(View view) {
		finish(view);
		if (!InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || !view.isAttachedToWindow() || ElementMotion.busy(view)) return;
		if (!(view.getParent() instanceof ViewGroup)) return;
		ViewGroup host = (ViewGroup) view.getParent(); finish(host);
		Session session = new Session(host, view, null, null); session.commit();
	}
	public static final class Session implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
		final ViewGroup host; final View incoming; final Bitmap bitmap;
		final float alpha, y; final int accessibility;
		private ValueAnimator animator; private OneShotPreDrawListener preDraw;
		private float progress; private boolean stopped, committed, bound;
		private final Runnable timeout = this::finish;
		private final Drawable layer = new Drawable() {
			private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
			@Override public void draw(Canvas canvas) {
				if (stopped || bitmap == null || bitmap.isRecycled()) return;
				paint.setAlpha(Math.round(255f * (1f - Math.min(progress / .35f, 1f))));
				canvas.drawBitmap(bitmap, null, getBounds(), paint);
			}
			@Override public void setAlpha(int alpha) {}
			@Override public void setColorFilter(ColorFilter filter) {}
			@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
		};
		Session(ViewGroup host, View incoming, Bitmap bitmap, Rect bounds) {
			this.host = host; this.incoming = incoming; this.bitmap = bitmap;
			alpha = incoming.getAlpha(); y = incoming.getTranslationY(); accessibility = incoming.getImportantForAccessibility();
			if (bounds != null) layer.setBounds(bounds);
			host.setTag(R.id.content_state_motion_owner, this); host.addOnAttachStateChangeListener(this); host.addOnLayoutChangeListener(this);
			host.postDelayed(timeout, 600);
		}
		public void commit() {
			if (committed || stopped) return; committed = true;
			if (!valid() || incoming.getVisibility() != View.VISIBLE) { finish(); return; }
			bindIncoming();
			if (bitmap != null) host.getOverlay().add(layer);
			preDraw = OneShotPreDrawListener.add(host, () -> {
				preDraw = null; if (!valid()) { finish(); return; }
				host.removeCallbacks(timeout);
				animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(InterfaceMotion.duration(bitmap != null ? 260 : 220));
				animator.setInterpolator(new android.view.animation.LinearInterpolator());
				animator.addUpdateListener(value -> {
					if (!valid()) { finish(); return; }
					progress = (float) value.getAnimatedValue();
					float phase = bitmap != null ? Math.max(0f, (progress - .35f) / .65f) : progress;
					float amount = InterfaceMotion.STANDARD.getInterpolation(Math.min(phase, 1f));
					incoming.setAlpha(alpha * amount); incoming.setTranslationY(y + 12f * ResourceUtils.obtainDensity(incoming) * (1f - amount));
					layer.invalidateSelf();
				});
				animator.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { finish(); } }); animator.start();
			});
		}
		void bindIncoming() {
			incoming.setTag(R.id.content_state_motion_owner, this); bound = true;
			incoming.setAlpha(0f); incoming.setTranslationY(y + 12f * ResourceUtils.obtainDensity(incoming));
			incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		}
		private boolean valid() {
			return !stopped && InterfaceMotion.isEnabled() && InterfaceMotion.duration(1) > 0 && host.isAttachedToWindow()
					&& host.getTag(R.id.content_state_motion_owner) == this && !ElementMotion.busy(host) && !ElementMotion.busy(incoming)
					&& (!bound || incoming.getTag(R.id.content_state_motion_owner) == this && incoming.getVisibility() == View.VISIBLE);
		}
		public void finish() {
			if (stopped) return; stopped = true;
			host.removeCallbacks(timeout); host.removeOnAttachStateChangeListener(this); host.removeOnLayoutChangeListener(this);
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (bound && incoming.getTag(R.id.content_state_motion_owner) == this) {
				if (!ElementMotion.busy(incoming)) { incoming.setAlpha(alpha); incoming.setTranslationY(y); incoming.setImportantForAccessibility(accessibility); }
				incoming.setTag(R.id.content_state_motion_owner, null);
			}
			if (host.getTag(R.id.content_state_motion_owner) == this) host.setTag(R.id.content_state_motion_owner, null);
			host.getOverlay().remove(layer); if (bitmap != null) bitmap.recycle();
			if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); }
		}
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
		@Override public void onLayoutChange(View view, int l, int t, int r, int b, int oldL, int oldT, int oldR, int oldB) {
			if (r - l != oldR - oldL || b - t != oldB - oldT) finish();
		}
	}
}
