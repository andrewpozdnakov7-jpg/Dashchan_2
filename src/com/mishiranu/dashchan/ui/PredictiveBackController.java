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
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.LinkedHashMap;
import java.util.Map;
import com.mishiranu.dashchan.widget.BackMotionSpec;
import com.mishiranu.dashchan.widget.BackPreviewBudget;

/** Pixels only. The Activity owns navigation; a cancelled gesture never creates or pops a fragment. */
final class PredictiveBackController {
	private static final int MAX_PREVIEWS = 4;
	private static final long PREPARE_DELAY_MS = 100;
	private final ViewGroup host;
	private final Runnable stateChanged;
	interface CaptureProvider { Bitmap capture(View view); }
	private final CaptureProvider captureProvider;
	private final LinkedHashMap<Object, Bitmap> previews = new LinkedHashMap<>(4, .75f, true);
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final RectF frame = new RectF();
	private final Path clip = new Path();
	private Bitmap outgoing, incoming;
	private Bitmap prepared;
	private Object currentKey, preparedKey;
	private long revision, preparedRevision;
	private Signature signature;
	private boolean capturing;
	private final Runnable prepareFrame = this::prepareFrame;
	private android.view.ViewTreeObserver drawObserver;
	private final android.view.ViewTreeObserver.OnDrawListener drawListener = () -> {
		if (!capturing && !isBusy() && currentKey != null) { revision++; schedulePreparation(); }
	};
	private Object destinationKey;
	private int generation;
	private ValueAnimator animator;
	private OneShotPreDrawListener preDraw;
	private boolean tracking, committing, animationDone, destinationReady;
	private float position, direction;
	private float initialTouchY, verticalOffset;
	private int width, height;
	private final Runnable timeout = this::finish;
	private final Drawable layer = new BaseDrawable() {
		@Override public void draw(Canvas canvas) { drawFrame(canvas); }
	};

	PredictiveBackController(ViewGroup host) {
		this(host, () -> {});
	}

	PredictiveBackController(ViewGroup host, Runnable stateChanged) {
		this(host, stateChanged, PredictiveBackController::capture);
	}
	PredictiveBackController(ViewGroup host, Runnable stateChanged, CaptureProvider captureProvider) {
		this.host = host;
		this.stateChanged = stateChanged;
		this.captureProvider = captureProvider;
		if (host.isAttachedToWindow()) observeDraws();
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) { observeDraws(); schedulePreparation(); }
			@Override public void onViewDetachedFromWindow(View view) {
				clear();
				if (drawObserver != null && drawObserver.isAlive()) drawObserver.removeOnDrawListener(drawListener);
				drawObserver = null;
			}
		});
		host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) {
				Object key = currentKey; clear(); if (key != null) prepare(key);
			}
		});
	}
	private void observeDraws() {
		if (drawObserver != null && drawObserver.isAlive()) drawObserver.removeOnDrawListener(drawListener);
		drawObserver = host.getViewTreeObserver(); drawObserver.addOnDrawListener(drawListener);
	}

	boolean isTracking() { return tracking; }
	boolean isBusy() { return outgoing != null; }
	boolean isCommitting() { return committing; }
	boolean isDestination(Object key) { return committing && key != null && key == destinationKey; }

	/** Called after real navigation/resume, never from a Back start or progress callback. */
	void prepare(Object key) { currentKey = key; revision++; schedulePreparation(); }
	private void schedulePreparation() {
		if (currentKey != null && enabled() && host.isAttachedToWindow()) {
			host.removeCallbacks(prepareFrame);
			host.postDelayed(prepareFrame, PREPARE_DELAY_MS);
		}
	}
	private void prepareFrame() {
		if (isBusy() || currentKey == null || !enabled() || !host.isAttachedToWindow()) return;
		Signature now = new Signature(host);
		if (signature != null && !signature.equals(now)) clearImages();
		signature = now;
		BackPreviewBudget.release(prepared); prepared = null;
		makeRoom(); capturing = true;
		Object expectedKey = currentKey; long expectedRevision = revision; int owner = generation;
		Bitmap result;
		try { result = captureProvider.capture(host); } finally { capturing = false; }
		if (currentKey != expectedKey || revision != expectedRevision || generation != owner || !now.equals(new Signature(host))) {
			BackPreviewBudget.release(result); return;
		}
		prepared = result; preparedKey = expectedKey; preparedRevision = expectedRevision;
	}
	private void makeRoom() {
		long limit = BackPreviewBudget.limit(host.getContext());
		while (!previews.isEmpty() && (previews.size() >= MAX_PREVIEWS || BackPreviewBudget.usedBytes() > limit / 2)) {
			Map.Entry<Object, Bitmap> eldest = previews.entrySet().iterator().next();
			BackPreviewBudget.release(eldest.getValue()); previews.remove(eldest.getKey());
		}
	}
	/** Includes presentation parameters; bitmap contents are last-known pixels, never a live old Fragment. */
	private static final class Signature {
		final int width, height, direction;
		final Object theme, insets;
		Signature(View view) {
			width = view.getWidth(); height = view.getHeight(); direction = view.getLayoutDirection();
			theme = ThemeEngine.getTheme(view.getContext()); insets = view.getRootWindowInsets();
		}
		@Override public boolean equals(Object other) {
			if (!(other instanceof Signature)) return false;
			Signature s = (Signature) other;
			return width == s.width && height == s.height && direction == s.direction && theme == s.theme
					&& java.util.Objects.equals(insets, s.insets);
		}
		@Override public int hashCode() { return java.util.Objects.hash(width, height, direction, theme, insets); }
	}

	void forget(Object key) {
		Bitmap old = previews.remove(key);
		if (old != null && old == incoming) finish();
		BackPreviewBudget.release(old);
	}

	void remember(Object key) {
		if (isBusy() || !enabled() || key == null) return;
		Signature now = new Signature(host);
		if (signature != null && !signature.equals(now)) clearImages();
		signature = now;
		int owner = generation; Object sourceKey = currentKey;
		Bitmap bitmap = preparedKey == key && preparedRevision == revision && !host.isDirty() && !host.isLayoutRequested() ? prepared : null;
		if (bitmap == prepared) prepared = null;
		if (bitmap == null) { BackPreviewBudget.release(prepared); prepared = null; makeRoom(); bitmap = captureProvider.capture(host); }
		if (generation != owner || currentKey != sourceKey || !now.equals(new Signature(host))) {
			BackPreviewBudget.release(bitmap); return;
		}
		Bitmap old = previews.remove(key);
		BackPreviewBudget.release(old);
		if (bitmap != null) previews.put(key, bitmap);
		currentKey = null; host.removeCallbacks(prepareFrame);
		while (previews.size() > MAX_PREVIEWS) {
			Map.Entry<Object, Bitmap> eldest = previews.entrySet().iterator().next();
			BackPreviewBudget.release(eldest.getValue()); previews.remove(eldest.getKey());
		}
	}

	boolean start(Object target, boolean fromLeft) {
		if (committing) return false;
		finish();
		if (!enabled() || !host.isAttachedToWindow()) return false;
		Bitmap preview = previews.get(target);
		// Reading time alone does not invalidate last-known destination pixels.
		// Signature/lifecycle invalidation and the shared memory budget still apply.
		// A restored / evicted destination has no trustworthy preview. Keep its normal Back action.
		if (preview == null || preview.isRecycled()) return false;
		// Prepared after a stable draw. No allocation, tree walk, or View.draw on hot-start.
		if (host.isLayoutRequested() || host.isDirty() || signature == null || !signature.equals(new Signature(host)) || preparedKey != currentKey
				|| preparedRevision != revision) return false;
		Bitmap current = prepared;
		if (current == null) return false;
		prepared = null;
		install(current, preview, fromLeft);
		destinationKey = target;
		return true;
	}
	boolean start(Object target, boolean fromLeft, float touchY) {
		boolean started = start(target, fromLeft);
		initialTouchY = Float.isFinite(touchY) ? touchY : 0f; verticalOffset = 0f;
		return started;
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
		position = BackMotionSpec.SCREEN_TRAVEL * clamp(progress);
		layer.invalidateSelf();
	}
	void progress(float progress, float touchY) {
		if (!tracking) return;
		float delta = Float.isFinite(touchY) ? touchY - initialTouchY : 0f;
		float limit = height * BackMotionSpec.MAX_VERTICAL;
		verticalOffset = Math.max(-limit, Math.min(limit, delta * BackMotionSpec.VERTICAL_RESPONSE));
		progress(progress);
	}

	void cancel() {
		if (!tracking) return;
		tracking = false;
		animateTo(0f, (int) BackMotionSpec.RECOVER_MS, this::finish);
	}

	boolean commit(Runnable navigation) {
		if (!tracking) return false;
		tracking = false; committing = true;
		animationDone = destinationReady = false;
		host.postDelayed(timeout, BackMotionSpec.DESTINATION_WAIT_MS);
		try {
			navigation.run();
			// Navigation can synchronously cause focus loss, layout, or teardown.
			if (committing) animateTo(1f, (int) BackMotionSpec.COMPLETE_MS, () -> { animationDone = true; finishIfReady(); });
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
			preDraw = null; destinationReady = true;
			// Only readiness is bounded. A valid Animator may legitimately run for 2s at 10x.
			host.removeCallbacks(timeout); finishIfReady();
		});
	}

	private void finishIfReady() { if (animationDone && destinationReady) finish(); }

	private void animateTo(float value, int duration, Runnable end) {
		if (!ValueAnimator.areAnimatorsEnabled() || !host.isAttachedToWindow()) { end.run(); return; }
		animator = ValueAnimator.ofFloat(position, value);
		animator.setDuration(duration);
		animator.setInterpolator(BackMotionSpec.EASING);
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
		if (wasBusy) host.getOverlay().remove(layer);
		if (outgoing != null) { BackPreviewBudget.release(outgoing); outgoing = null; }
		incoming = null; destinationKey = null; position = verticalOffset = 0f;
		if (wasBusy) stateChanged.run();
		if (wasBusy) { revision++; schedulePreparation(); }
	}

	void clear() {
		finish();
		currentKey = null; host.removeCallbacks(prepareFrame);
		clearImages(); signature = null;
	}
	private void clearImages() {
		BackPreviewBudget.release(prepared); prepared = null; preparedKey = null;
		for (Bitmap bitmap : previews.values()) BackPreviewBudget.release(bitmap);
		previews.clear();
	}

	private void drawFrame(Canvas canvas) {
		if (outgoing == null || incoming == null) return;
		canvas.drawColor(ThemeEngine.getTheme(host.getContext()).window);
		frame.set(0, 0, width, height);
		frame.offset(-direction * width * BackMotionSpec.SCREEN_PARALLAX * (1f - position), 0f);
		paint.setAlpha(255); canvas.drawBitmap(incoming, null, frame, paint);
		canvas.drawColor(Math.round(BackMotionSpec.SCREEN_SCRIM_ALPHA * (1f - position)) << 24);
		float shrink = Math.min(1f, position / BackMotionSpec.SCREEN_TRAVEL);
		float insetX = width * BackMotionSpec.SCREEN_INSET * shrink, insetY = height * BackMotionSpec.SCREEN_INSET * shrink;
		frame.set(insetX, insetY, width - insetX, height - insetY);
		frame.offset(direction * width * position, verticalOffset * shrink);
		float radius = BackMotionSpec.SCREEN_RADIUS_DP * ResourceUtils.obtainDensity(host) * shrink;
		clip.reset(); clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
		int save = canvas.save(); canvas.clipPath(clip);
		canvas.drawBitmap(outgoing, null, frame, paint); canvas.restoreToCount(save);
	}

	static float clamp(float value) { return Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value)); }

	static boolean externalSurface(View view) {
		return com.mishiranu.dashchan.widget.PredictiveBackMotion.hasExternalSurface(view);
	}

	static Bitmap capture(View view) {
		return BackPreviewBudget.capture(view, BackPreviewBudget.limit(view.getContext()) / 2,
				ThemeEngine.getTheme(view.getContext()).window);
	}
}
