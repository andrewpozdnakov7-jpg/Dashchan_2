package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pixels only. The Activity owns navigation; a cancelled gesture never creates or pops a fragment. */
final class PredictiveBackController {
	private static final int MAX_PIXELS = 500_000, MAX_PREVIEWS = 4;
	private final ViewGroup host;
	private final Runnable stateChanged;
	private final LinkedHashMap<Object, Bitmap> previews = new LinkedHashMap<>(4, .75f, true);
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final RectF frame = new RectF();
	private final Path clip = new Path();
	private Bitmap outgoing, incoming;
	private Object destinationKey;
	private int generation;
	private ValueAnimator animator;
	private OneShotPreDrawListener preDraw;
	private boolean tracking, committing, animationDone, destinationReady;
	private float position, direction;
	private int width, height;
	private final Runnable timeout = this::finish;
	private final Drawable layer = new BaseDrawable() {
		@Override public void draw(Canvas canvas) { drawFrame(canvas); }
	};

	PredictiveBackController(ViewGroup host) {
		this(host, () -> {});
	}

	PredictiveBackController(ViewGroup host, Runnable stateChanged) {
		this.host = host;
		this.stateChanged = stateChanged;
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { clear(); }
		});
		host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) clear();
		});
	}

	boolean isTracking() { return tracking; }
	boolean isBusy() { return outgoing != null; }
	boolean isCommitting() { return committing; }
	boolean isDestination(Object key) { return committing && key != null && key == destinationKey; }

	void forget(Object key) {
		Bitmap old = previews.remove(key);
		if (old != null && old == incoming) finish();
		if (old != null) old.recycle();
	}

	void remember(Object key) {
		if (isBusy() || !enabled() || key == null) return;
		Bitmap bitmap = capture(host);
		Bitmap old = previews.remove(key);
		if (old != null) old.recycle();
		if (bitmap != null) previews.put(key, bitmap);
		while (previews.size() > MAX_PREVIEWS) {
			Map.Entry<Object, Bitmap> eldest = previews.entrySet().iterator().next();
			eldest.getValue().recycle(); previews.remove(eldest.getKey());
		}
	}

	boolean start(Object target, boolean fromLeft) {
		if (committing) return false;
		finish();
		if (!enabled() || !host.isAttachedToWindow()) return false;
		Bitmap preview = previews.get(target);
		// A restored / evicted destination has no trustworthy preview. Keep its normal Back action.
		if (preview == null || preview.isRecycled()) return false;
		Bitmap current = capture(host);
		if (current == null) return false;
		install(current, preview, fromLeft);
		destinationKey = target;
		return true;
	}

	private boolean enabled() {
		return Build.VERSION.SDK_INT >= 34 && InterfaceMotion.isPredictiveBackEnabled() && ValueAnimator.areAnimatorsEnabled();
	}

	/** Kept separate from policy/capture so pixel ownership can be tested without device preferences. */
	void install(Bitmap current, Bitmap previous, boolean fromLeft) {
		finish();
		outgoing = current; incoming = previous;
		width = host.getWidth(); height = host.getHeight();
		direction = fromLeft ? 1f : -1f; position = 0f; tracking = true;
		layer.setBounds(0, 0, width, height);
		host.getOverlay().add(layer);
		host.invalidate();
		stateChanged.run();
	}

	void progress(float progress) {
		if (!tracking) return;
		position = .35f * clamp(progress);
		layer.invalidateSelf();
	}

	void cancel() {
		if (!tracking) return;
		tracking = false;
		animateTo(0f, 180, this::finish);
	}

	boolean commit(Runnable navigation) {
		if (!tracking) return false;
		tracking = false; committing = true;
		animationDone = destinationReady = false;
		host.postDelayed(timeout, 700);
		try {
			navigation.run();
			// Navigation can synchronously cause focus loss, layout, or teardown.
			if (committing) animateTo(1f, 180, () -> { animationDone = true; finishIfReady(); });
		} catch (RuntimeException | Error e) {
			finish(); throw e;
		}
		return true;
	}

	/** Called from the actual destination's runOnCommit, then wait for its first laid-out frame. */
	void destinationCommitted(Object key) {
		if (!committing || key != destinationKey) return;
		if (preDraw != null) preDraw.removeListener();
		int owner = generation;
		preDraw = OneShotPreDrawListener.add(host, () -> {
			if (generation != owner || !committing) return;
			preDraw = null; destinationReady = true; finishIfReady();
		});
	}

	private void finishIfReady() { if (animationDone && destinationReady) finish(); }

	private void animateTo(float value, int duration, Runnable end) {
		if (!ValueAnimator.areAnimatorsEnabled() || !host.isAttachedToWindow()) { end.run(); return; }
		animator = ValueAnimator.ofFloat(position, value);
		animator.setDuration(duration);
		animator.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
		animator.addUpdateListener(a -> { position = (float) a.getAnimatedValue(); layer.invalidateSelf(); });
		animator.addListener(new AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(Animator a) { if (animator == a) { animator = null; end.run(); } }
		});
		animator.start();
	}

	void finish() {
		boolean wasBusy = isBusy();
		generation++;
		tracking = committing = false;
		ValueAnimator previous = animator; animator = null;
		if (previous != null) { previous.removeAllUpdateListeners(); previous.removeAllListeners(); previous.cancel(); }
		host.removeCallbacks(timeout);
		if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
		host.getOverlay().remove(layer);
		if (outgoing != null) { outgoing.recycle(); outgoing = null; }
		incoming = null; destinationKey = null; position = 0f;
		if (wasBusy) stateChanged.run();
	}

	void clear() {
		finish();
		for (Bitmap bitmap : previews.values()) bitmap.recycle();
		previews.clear();
	}

	private void drawFrame(Canvas canvas) {
		if (outgoing == null || incoming == null) return;
		canvas.drawColor(ThemeEngine.getTheme(host.getContext()).window);
		frame.set(0, 0, width, height);
		frame.offset(-direction * width * .12f * (1f - position), 0f);
		paint.setAlpha(255); canvas.drawBitmap(incoming, null, frame, paint);
		canvas.drawColor(Math.round(30f * (1f - position)) << 24);
		float shrink = Math.min(1f, position / .35f);
		float insetX = width * .04f * shrink, insetY = height * .04f * shrink;
		frame.set(insetX, insetY, width - insetX, height - insetY);
		frame.offset(direction * width * position, 0f);
		float radius = 24f * ResourceUtils.obtainDensity(host) * shrink;
		clip.reset(); clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
		int save = canvas.save(); canvas.clipPath(clip);
		canvas.drawBitmap(outgoing, null, frame, paint); canvas.restoreToCount(save);
	}

	static float clamp(float value) { return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value)); }

	static boolean externalSurface(View view) {
		return com.mishiranu.dashchan.widget.PredictiveBackMotion.hasExternalSurface(view);
	}

	static Bitmap capture(View view) {
		if (view.getWidth() <= 0 || view.getHeight() <= 0 || externalSurface(view)) return null;
		Bitmap bitmap = null;
		try {
			double scale = Math.min(1d, Math.sqrt(MAX_PIXELS / ((double) view.getWidth() * view.getHeight())));
			int w = Math.max(1, (int) (view.getWidth() * scale));
			int h = Math.max(1, (int) (view.getHeight() * scale));
			if ((long) w * h > MAX_PIXELS) h = Math.max(1, MAX_PIXELS / w);
			bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap); canvas.drawColor(ThemeEngine.getTheme(view.getContext()).window);
			canvas.scale(w / (float) view.getWidth(), h / (float) view.getHeight());
			view.draw(canvas); return bitmap;
		} catch (RuntimeException | OutOfMemoryError e) {
			if (bitmap != null) bitmap.recycle(); return null;
		}
	}
}
