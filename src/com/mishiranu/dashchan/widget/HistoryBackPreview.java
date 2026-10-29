package com.mishiranu.dashchan.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.function.BooleanSupplier;

/** Frozen pixels only: no live navigation, focus, scroll, alpha or scale changes during tracking. */
public final class HistoryBackPreview {
	private final View host;
	private final BackPreviewFrame previous, current;
	private final BooleanSupplier owner;
	private final int width, height, scrollX, scrollY;
	private final float direction;
	private float fraction;
	private boolean accepted, holding, released;
	private final PredictiveTransition transition;
	private final Runnable deadline = this::close;
	private final Runnable ready = this::close;
	private final Drawable layer = new Drawable() {
		final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		final RectF bounds = new RectF();
		@Override public void draw(Canvas canvas) {
			if (released) return;
			int save = canvas.save();
			canvas.translate(scrollX, scrollY); canvas.clipRect(0, 0, width, height);
			canvas.drawColor(previous.background);
			bounds.set(0, 0, width, previous.height);
			paint.setAlpha(255); canvas.drawBitmap(previous.image, null, bounds, paint);
			if (!holding) {
				bounds.set(0, 0, width, height);
				float shift = direction * width * fraction;
				bounds.offset(shift, 0); paint.setAlpha(255);
				canvas.drawBitmap(current.image, null, bounds, paint);
			}
			canvas.restoreToCount(save);
		}
		@Override public void setAlpha(int value) {}
		@Override public void setColorFilter(ColorFilter value) {}
		@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	};
	private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
		@Override public void onViewAttachedToWindow(View view) {}
		@Override public void onViewDetachedFromWindow(View view) { close(); }
	};
	public static HistoryBackPreview start(View host, BackPreviewFrame previous, BackPreviewFrame current,
			boolean fromLeft, BooleanSupplier owner) {
		if (previous == null || current == null || !previous.valid(host) || !current.valid(host)
				|| current.height != host.getHeight() || !owner.getAsBoolean()) return null;
		return new HistoryBackPreview(host, previous, current, fromLeft, owner);
	}
	private HistoryBackPreview(View host, BackPreviewFrame previous, BackPreviewFrame current,
			boolean fromLeft, BooleanSupplier owner) {
		this.host = host; this.previous = previous.retain(); this.current = current.retain(); this.owner = owner;
		width = host.getWidth(); height = host.getHeight(); scrollX = host.getScrollX(); scrollY = host.getScrollY();
		direction = fromLeft ? 1f : -1f;
		layer.setBounds(0, 0, width, height); host.getOverlay().add(layer);
		host.addOnAttachStateChangeListener(attachment);
		transition = new PredictiveTransition(host, new PredictiveTransition.Presentation() {
			@Override public boolean valid() { return !released && owner.getAsBoolean() && previous.valid(host)
					&& current.valid(host) && host.getHeight() == height && host.getScrollX() == scrollX && host.getScrollY() == scrollY; }
			@Override public void apply(float value) { fraction = value; layer.invalidateSelf(); }
			@Override public void restore() { if (!accepted) release(); }
		});
	}
	public boolean isBusy() { return !released; }
	public boolean isCommitting() { return !released && accepted; }
	public boolean isTracking() { return !released && transition.isTracking(); }
	public void progress(float fraction) { transition.progress(fraction); }
	public void cancel() { transition.cancel(); }
	public boolean commit(Runnable action) {
		if (!isTracking() || !owner.getAsBoolean()) { close(); return false; }
		accepted = true;
		boolean result = transition.commit(() -> {
			holding = true; fraction = 1f; layer.invalidateSelf();
			// This bounds only asynchronous document readiness, never a running animator.
			host.postDelayed(deadline, 5000);
			action.run();
		});
		if (!result) { accepted = false; release(); }
		return result;
	}
	/** Called after destination layout / WebView visual readiness, then wait for its next draw. */
	public void destinationReady() { if (holding && !released) { host.removeCallbacks(ready); host.postOnAnimation(ready); } }
	public void close() { transition.finish(); release(); }
	private void release() {
		if (released) return;
		released = true; host.removeCallbacks(deadline); host.removeCallbacks(ready);
		host.removeOnAttachStateChangeListener(attachment); host.getOverlay().remove(layer);
		previous.release(); current.release();
	}
}
