package com.mishiranu.dashchan.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.LinkedHashMap;
import java.util.Map;
import com.mishiranu.dashchan.widget.BackMotionSpec;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.PredictiveBackLayout;
import com.mishiranu.dashchan.media.VideoDiagnostics;

/** Live current page over cached destination pixels. The Activity alone owns navigation. */
final class PredictiveBackController {
	private final ViewGroup host;
	private final PredictiveBackLayout liveHost;
	private final PredictiveBackLayout.Presentation livePresentation = this::drawLiveFrame;
	private final Runnable stateChanged;
	interface CaptureProvider { Bitmap capture(View view); }
	private final CaptureProvider captureProvider;
	private static final class Preview {
		final Bitmap image;
		final Signature signature;
		Preview(Bitmap image, Signature signature) { this.image = image; this.signature = signature; }
	}
	private final LinkedHashMap<Object, Preview> previews = new LinkedHashMap<>(4, .75f, true);
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final RectF frame = new RectF();
	private final Path clip = new Path();
	private Bitmap outgoing, incoming;
	private boolean busy, incomingLeased;
	private Object currentKey, nearestKey;
	private Signature signature;
	private boolean capturing;
	private final BackPreviewBudget.Cache cache = this::evictOldest;
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
		liveHost = host instanceof PredictiveBackLayout ? (PredictiveBackLayout) host : null;
		this.stateChanged = stateChanged;
		this.captureProvider = captureProvider;
		BackPreviewBudget.registerCache(cache);
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { clear("host_detached"); }
		});
		host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) {
				// A resized live surface must end its gesture, but IME-driven child layout
				// does not invalidate snapshots from the unchanged containing window.
				finish();
				if (signature != null) updateSignature(new Signature(host), "layout");
				recordState("host_layout_changed old_width=" + (or - ol) + " old_height=" + (ob - ot));
			}
		});
	}
	boolean isTracking() { return tracking; }
	boolean isBusy() { return busy; }
	boolean isCommitting() { return committing; }
	boolean isDestination(Object key) { return committing && key != null && key == destinationKey; }

	/** Records route identity only: no delayed capture and no work on each draw. */
	void prepare(Object key) { prepare(key, null); }
	void prepare(Object key, Object previousKey) {
		currentKey = key; nearestKey = previousKey;
		// The current page is live; its old cached pixels are redundant.
		forget(key, "current_page_live");
		recordState("prepare");
	}
	private Bitmap captureCurrent() {
		capturing = true;
		try { return captureProvider.capture(host); } finally { capturing = false; }
	}
	private boolean evictOldest() {
		if (previews.isEmpty()) return false;
		Map.Entry<Object, Preview> eldest = null;
		for (Map.Entry<Object, Preview> entry : previews.entrySet()) {
			if (entry.getKey() != nearestKey) { eldest = entry; break; }
		}
		// Only sacrifice the immediate Back destination after every other cached page.
		if (eldest == null) eldest = previews.entrySet().iterator().next();
		boolean nearest = eldest.getKey() == nearestKey;
		Bitmap bitmap = eldest.getValue().image; previews.remove(eldest.getKey());
		BackPreviewBudget.release(bitmap);
		recordState("evict_oldest removed_previous=" + nearest);
		return true;
	}
	/** Separates stable window presentation from the transient drawable viewport (e.g. the IME). */
	private static final class Signature {
		final int width, height, direction, orientation, rotation, density;
		final float fontScale;
		final Rect windowBounds;
		final Object theme, insets;
		Signature(View view) {
			width = view.getWidth(); height = view.getHeight(); direction = view.getLayoutDirection();
			android.content.res.Configuration configuration = view.getResources().getConfiguration();
			orientation = configuration.orientation; density = configuration.densityDpi;
			fontScale = configuration.fontScale;
			rotation = view.getDisplay() != null ? view.getDisplay().getRotation() : 0;
			WindowManager manager = view.getContext().getSystemService(WindowManager.class);
			// Window bounds include inset areas and do not shrink with our IME bottom margin.
			windowBounds = Build.VERSION.SDK_INT >= 30 && manager != null
					? new Rect(manager.getCurrentWindowMetrics().getBounds())
					: new Rect(0, 0, view.getRootView().getWidth(), view.getRootView().getHeight());
			theme = ThemeEngine.getTheme(view.getContext());
			android.view.WindowInsets windowInsets = view.getRootWindowInsets();
			insets = Build.VERSION.SDK_INT >= 30 && windowInsets != null ? windowInsets.getInsetsIgnoringVisibility(
					android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()) : null;
		}
		boolean sameWindow(Signature other) {
			return direction == other.direction && orientation == other.orientation && rotation == other.rotation
					&& density == other.density && Float.compare(fontScale, other.fontScale) == 0
					&& windowBounds.equals(other.windowBounds) && theme == other.theme
					&& java.util.Objects.equals(insets, other.insets);
		}
		@Override public boolean equals(Object other) {
			if (!(other instanceof Signature)) return false;
			Signature s = (Signature) other;
			return width == s.width && height == s.height && sameWindow(s);
		}
		@Override public int hashCode() {
			return java.util.Objects.hash(width, height, direction, orientation, rotation, density,
					fontScale, windowBounds, theme, insets);
		}
	}
	private void updateSignature(Signature now, String stage) {
		if (signature != null && !signature.sameWindow(now)) {
			recordSignatureChange(stage, now);
			clearImages(stage + "_window_changed");
		}
		signature = now;
	}

	void forget(Object key) {
		forget(key, "navigation");
	}
	private void forget(Object key, String reason) {
		Preview old = previews.remove(key);
		// An active preview holds its own lease, independent of cache ownership.
		if (old != null) BackPreviewBudget.release(old.image);
		if (old != null) recordState("forget reason=" + reason + " removed_previous=" + (key == nearestKey));
	}

	void remember(Object key) {
		if (isBusy() || !enabled() || key == null) return;
		Signature now = new Signature(host);
		updateSignature(now, "remember");
		int owner = generation; Object sourceKey = currentKey;
		forget(key, "replace_snapshot");
		Bitmap bitmap = captureCurrent();
		if (generation != owner || currentKey != sourceKey || !now.equals(new Signature(host))) {
			recordState("capture_discarded generation_changed=" + (generation != owner)
					+ " current_changed=" + (currentKey != sourceKey));
			BackPreviewBudget.release(bitmap); return;
		}
		if (bitmap != null) previews.put(key, new Preview(bitmap, now));
		currentKey = null;
		record("remember stored=" + (bitmap != null) + " cached_pages=" + previews.size()
				+ " used_bytes=" + BackPreviewBudget.usedBytes() + " budget_bytes=" + BackPreviewBudget.limit(host.getContext()));
	}

	boolean start(Object target, boolean fromLeft) {
		if (committing) return false;
		finish();
		if (!enabled() || liveHost == null || !host.isAttachedToWindow() || host.getWidth() <= 0 || host.getHeight() <= 0
				|| externalSurface(host)) return false;
		Signature now = new Signature(host);
		updateSignature(now, "start");
		nearestKey = target;
		Preview saved = previews.get(target);
		if (saved != null && saved.image.isRecycled()) {
			previews.remove(target); saved = null; recordState("start_recycled_previous");
		}
		// Keep other viewport sizes cached, but never stretch their pixels into this viewport.
		Bitmap preview = saved != null && saved.signature.equals(now) ? saved.image : null;
		if (saved != null && preview == null) recordState("start_viewport_mismatch saved_width="
				+ saved.signature.width + " saved_height=" + saved.signature.height);
		// A missing destination uses the window surface, never an unrelated cached page.
		// Current pixels are drawn live: touch feedback or a new draw cannot invalidate the start.
		install(null, preview, fromLeft);
		destinationKey = target;
		recordState("start live=1 previous_cached=" + (preview != null));
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
		incomingLeased = BackPreviewBudget.retain(previous);
		busy = true;
		width = host.getWidth(); height = host.getHeight();
		direction = fromLeft ? 1f : -1f; position = 0f; tracking = true;
		layer.setBounds(0, 0, width, height);
		if (liveHost != null) liveHost.setPresentation(livePresentation); else host.getOverlay().add(layer);
		host.invalidate();
		stateChanged.run();
	}

	void progress(float progress) {
		if (!tracking) return;
		position = BackMotionSpec.SCREEN_TRAVEL * clamp(progress);
		invalidate();
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
		record("cancel");
		tracking = false;
		animateTo(0f, (int) BackMotionSpec.RECOVER_MS, this::finish);
	}

	boolean commit(Runnable navigation) {
		if (!tracking) return false;
		if (liveHost != null && outgoing == null) {
			int owner = generation;
			// Freeze once at confirmation, before navigation replaces the live hierarchy.
			// captureCurrent bypasses the presentation, so the snapshot is untransformed.
			Bitmap bitmap = captureCurrent();
			if (generation != owner || !tracking) {
				BackPreviewBudget.release(bitmap);
				recordState("commit_capture_invalidated consumed=true");
				// The old gesture no longer owns the surface. Do not fall back on a new session/route.
				return true;
			}
			if (bitmap == null) {
				recordState("commit_capture_failed fallback=normal_back");
				finish();
				// No callback was executed: MainActivity owns exactly one ordinary Back.
				return false;
			}
			outgoing = bitmap;
		}
		tracking = false; committing = true;
		record("commit source_frozen=" + (outgoing != null));
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
		animator.addUpdateListener(a -> { position = (float) a.getAnimatedValue(); invalidate(); });
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
		busy = false;
		if (wasBusy) {
			if (liveHost != null) liveHost.setPresentation(null); else host.getOverlay().remove(layer);
		}
		if (outgoing != null) { BackPreviewBudget.release(outgoing); outgoing = null; }
		if (incomingLeased) BackPreviewBudget.release(incoming);
		incomingLeased = false;
		incoming = null; destinationKey = null; position = verticalOffset = 0f;
		if (wasBusy) stateChanged.run();
	}

	void clear() {
		clear("explicit");
	}
	void clear(String reason) {
		recordState("clear_requested reason=" + reason);
		finish();
		currentKey = nearestKey = null;
		clearImages(reason);
		signature = null;
	}
	/** Drop cache ownership without interrupting a gesture or recycling its leased destination. */
	void trimMemory() { trimMemory(-1); }
	void trimMemory(int level) {
		if (com.mishiranu.dashchan.widget.PreviewMemoryPolicy.shouldTrim(level)) {
			clearImages("trim_memory level=" + level);
		} else {
			recordState("trim_memory_retained level=" + level);
		}
	}
	void close() { clear("controller_closed"); BackPreviewBudget.unregisterCache(cache); }
	private void clearImages(String reason) {
		int removed = previews.size();
		recordState("cache_clear_begin reason=" + reason);
		for (Preview preview : previews.values()) BackPreviewBudget.release(preview.image);
		previews.clear();
		recordState("cache_clear_end reason=" + reason + " removed_pages=" + removed);
	}
	private void invalidate() { if (liveHost != null) host.invalidate(); else layer.invalidateSelf(); }
	/** Code-owned event labels and counts only; never log route keys, page content or snapshot pixels. */
	void recordState(String event) {
		if (!VideoDiagnostics.isExtendedRecording()) return;
		record(event + " cached_pages=" + previews.size() + " previous_entry=" + previews.containsKey(nearestKey)
				+ " current_known=" + (currentKey != null) + " previous_known=" + (nearestKey != null)
				+ " busy=" + busy + " attached=" + host.isAttachedToWindow()
				+ " width=" + host.getWidth() + " height=" + host.getHeight()
				+ " shared_used_bytes=" + BackPreviewBudget.usedBytes());
	}
	private void recordSignatureChange(String stage, Signature now) {
		if (!VideoDiagnostics.isExtendedRecording()) return;
		record("signature_changed stage=" + stage
				+ " size_changed=" + (signature.width != now.width || signature.height != now.height)
				+ " direction_changed=" + (signature.direction != now.direction)
				+ " window_changed=" + !signature.windowBounds.equals(now.windowBounds)
				+ " orientation_changed=" + (signature.orientation != now.orientation || signature.rotation != now.rotation)
				+ " density_changed=" + (signature.density != now.density || Float.compare(signature.fontScale, now.fontScale) != 0)
				+ " theme_changed=" + (signature.theme != now.theme)
				+ " insets_changed=" + !java.util.Objects.equals(signature.insets, now.insets));
	}
	private static void record(String message) {
		if (VideoDiagnostics.isExtendedRecording()) {
			android.util.Log.i("SlooopPredictiveBack", "predictive_page " + message);
			VideoDiagnostics.recordUi("predictive_page " + message);
		}
	}
	private void drawLiveFrame(Canvas canvas, PredictiveBackLayout.Content content) {
		if (capturing || !busy) content.draw(canvas); else drawFrame(canvas, content);
	}

	private void drawFrame(Canvas canvas) {
		drawFrame(canvas, null);
	}
	private void drawFrame(Canvas canvas, PredictiveBackLayout.Content content) {
		if (!busy) return;
		canvas.drawColor(ThemeEngine.getTheme(host.getContext()).window);
		frame.set(0, 0, width, height);
		frame.offset(-direction * width * BackMotionSpec.SCREEN_PARALLAX * (1f - position), 0f);
		paint.setAlpha(255);
		if (incoming != null && !incoming.isRecycled()) canvas.drawBitmap(incoming, null, frame, paint);
		canvas.drawColor(Math.round(BackMotionSpec.SCREEN_SCRIM_ALPHA * (1f - position)) << 24);
		float shrink = Math.min(1f, position / BackMotionSpec.SCREEN_TRAVEL);
		float insetX = width * BackMotionSpec.SCREEN_INSET * shrink, insetY = height * BackMotionSpec.SCREEN_INSET * shrink;
		frame.set(insetX, insetY, width - insetX, height - insetY);
		frame.offset(direction * width * position, verticalOffset * shrink);
		float radius = BackMotionSpec.SCREEN_RADIUS_DP * ResourceUtils.obtainDensity(host) * shrink;
		clip.reset(); clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
		int save = canvas.save(); canvas.clipPath(clip);
		if (outgoing != null) canvas.drawBitmap(outgoing, null, frame, paint);
		else if (!committing && content != null) {
			canvas.translate(frame.left, frame.top);
			canvas.scale(frame.width() / width, frame.height() / height);
			canvas.drawColor(ThemeEngine.getTheme(host.getContext()).window);
			content.draw(canvas);
		}
		canvas.restoreToCount(save);
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
