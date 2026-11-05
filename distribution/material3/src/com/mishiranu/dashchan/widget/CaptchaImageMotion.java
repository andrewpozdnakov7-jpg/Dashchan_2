package com.mishiranu.dashchan.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Per-image replacement. The new pixels stay opaque; only the old overlay fades out. */
final class CaptchaImageMotion implements AutoCloseable {
	private static final long MAX_SNAPSHOT_PIXELS = 2048L * 2048L;
	private final IdentityHashMap<ImageView, Change> changes = new IdentityHashMap<>();
	private long snapshotPixels;
	private boolean closed;

	void bind(ImageView view, Bitmap next, boolean animate) {
		if (closed) return;
		Change previous = changes.get(view); if (previous != null) previous.finish();
		Drawable drawable = view.getDrawable();
		Bitmap old = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap() : null;
		// Server responses decode fresh Bitmap objects even for symbols whose pixels did not change.
		if (old == next || old != null && next != null && !old.isRecycled() && !next.isRecycled() && old.sameAs(next)) return;
		long pixels = (long) view.getWidth() * view.getHeight();
		if (!animate || drawable == null || next == null || !view.isAttachedToWindow() || !view.isShown()
				|| pixels <= 0 || pixels > MAX_SNAPSHOT_PIXELS - snapshotPixels || InterfaceMotion.duration(160) == 0) {
			view.setImageBitmap(next); return;
		}
		// Capture this one captcha ImageView, never the dialog, form, user text or response token.
		Bitmap snapshot = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
		SnapshotDrawing.draw(view, new Canvas(snapshot));
		Change change = new Change(view, snapshot, pixels); changes.put(view, change); snapshotPixels += pixels;
		view.setImageBitmap(next); // Do not fade the real image or its clickable/accessibility parent.
		change.start();
	}
	void finish() { for (Change change : new ArrayList<>(changes.values())) change.finish(); }
	@Override public void close() { closed = true; finish(); }

	private final class Change implements View.OnAttachStateChangeListener {
		final ImageView view;
		final BitmapDrawable overlay;
		final long pixels;
		ValueAnimator animator;
		boolean finished;
		Change(ImageView view, Bitmap snapshot, long pixels) {
			this.view = view; this.pixels = pixels;
			overlay = new BitmapDrawable(view.getResources(), snapshot);
			overlay.setBounds(0, 0, view.getWidth(), view.getHeight());
			view.getOverlay().add(overlay); view.addOnAttachStateChangeListener(this);
		}
		void start() {
			ValueAnimator running = ValueAnimator.ofFloat(0f, 1f); animator = running;
			running.setDuration(InterfaceMotion.duration(160)); running.setInterpolator(InterfaceMotion.STANDARD);
			running.addUpdateListener(value -> {
				if (finished || animator != value) return;
				if (InterfaceMotion.duration(1) == 0) { finish(); return; }
				overlay.setAlpha(Math.round(255 * (1 - (float) value.getAnimatedValue())));
			});
			running.addListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationEnd(Animator animation) { finish(); }
			}); running.start();
		}
		void finish() {
			if (finished) return;
			finished = true; ValueAnimator old = animator; animator = null;
			if (old != null) { old.removeAllUpdateListeners(); old.removeAllListeners(); old.cancel(); }
			view.getOverlay().remove(overlay); view.removeOnAttachStateChangeListener(this);
			if (changes.get(view) == this) { changes.remove(view); snapshotPixels -= pixels; }
		}
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { finish(); }
	}
}
