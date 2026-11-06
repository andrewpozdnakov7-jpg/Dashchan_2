package com.mishiranu.dashchan.ui;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import androidx.core.view.OneShotPreDrawListener;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.ui.posting.PostingFragment;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.util.ReplySwipeOverlapSpec;
import com.mishiranu.dashchan.widget.SnapshotDrawing;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.PreviewMemoryPolicy;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import java.util.function.BooleanSupplier;

/** Activity-owned pixels for the last frames of an accepted swipe, independent of the posts view. */
public final class ReplySwipeOverlap implements AutoCloseable {
	public interface Request extends BooleanSupplier {
		long releasedAtUptime();
		boolean prepare(ReplySwipeOverlap overlap, View source, PostingFragment destination);
	}

	static final class Clock {
		private final ValueAnimator animator = ValueAnimator.ofFloat(0f, ReplySwipeOverlapSpec.END_MS);
		private Runnable frame;
		private float time;

		Clock(Runnable frame) {
			this.frame = frame;
			animator.setDuration(ReplySwipeOverlapSpec.END_MS);
			animator.setInterpolator(new LinearInterpolator());
			animator.addUpdateListener(animation -> {
				time = (float) animation.getAnimatedValue();
				if (this.frame != null) this.frame.run();
			});
		}

		float time() { return time; }
		void start() { animator.start(); }
		void transfer(Runnable frame) { this.frame = frame; }
		void stop() {
			frame = null;
			animator.removeAllUpdateListeners();
			animator.cancel();
		}
	}

	private static final int SCENE_PIXELS = 850_000, ROW_PIXELS = 260_000;
	private final ViewGroup host;
	private final PreviewMemoryPolicy memory;
	private Session active;
	private boolean closed;

	ReplySwipeOverlap(ViewGroup host) {
		this.host = host;
		memory = new PreviewMemoryPolicy(host.getContext(), reason -> finish());
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) { finish(); }
		});
		host.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (r - l != oldR - oldL || b - t != oldB - oldT) finish();
		});
	}

	boolean prepare(View source, ViewGroup list, View row, PostingFragment destination, Clock clock, float releaseX) {
		finish();
		if (closed || !InterfaceMotion.isEnabled() || InterfaceMotion.duration(1) == 0 || source == null
				|| list == null || row == null || destination == null || clock == null || !Float.isFinite(releaseX)
				|| !host.isAttachedToWindow() || !source.isAttachedToWindow() || !row.isShown()
				|| row.getParent() != list || ScreenMotionController.externalSurface(source)) return false;
		if (PreviewMemoryPolicy.isMemoryLow(host.getContext())) {
			PreviewMemoryPolicy.reclaim(host.getContext(), "reply_overlap_low_memory");
			return false;
		}

		RectF sceneBounds = bounds(source);
		RectF listBounds = bounds(list);
		Rect listVisible = new Rect();
		if (!list.getLocalVisibleRect(listVisible)) return false;
		listVisible.offset(Math.round(listBounds.left), Math.round(listBounds.top));
		RectF clip = new RectF(listVisible);
		if (!clip.intersect(sceneBounds) || !clip.intersect(0, 0, host.getWidth(), host.getHeight())) return false;
		RectF rowBounds = bounds(row);
		rowBounds.offset(-row.getTranslationX(), 0f);
		int rowTop = Math.max(0, (int) Math.floor(clip.top - rowBounds.top));
		int rowBottom = Math.min(row.getHeight(), (int) Math.ceil(clip.bottom - rowBounds.top));
		int height = rowBottom - rowTop;
		int width = row.getWidth();
		if (height <= 0 || width <= 0) return false;
		rowBounds.bottom = rowBounds.top + rowBottom;
		rowBounds.top += rowTop;

		Bitmap card = null, scene = null;
		long captureStarted = android.os.SystemClock.elapsedRealtime();
		try {
			double scale = Math.min(1d, Math.sqrt(ROW_PIXELS / ((double) width * height)));
			card = Bitmap.createBitmap(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)),
					Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(card);
			// An opaque row surface covers its captured reply indicator while returning over the scene.
			canvas.drawColor(ThemeEngine.getTheme(host.getContext()).window);
			canvas.scale(card.getWidth() / (float) width, card.getHeight() / (float) height);
			canvas.translate(0f, -rowTop);
			SnapshotDrawing.draw(row, canvas);
			int visibility = row.getVisibility();
			row.setVisibility(View.INVISIBLE);
			try {
				scene = ScreenMotionController.capture(source, SCENE_PIXELS,
						ThemeEngine.getTheme(host.getContext()).window);
			} finally {
				row.setVisibility(visibility);
			}
			if (scene == null) return false;
			active = new Session(destination, clock, releaseX, scene, sceneBounds, card, rowBounds, clip);
			if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("reply_overlap prepared capture_ms="
					+ (android.os.SystemClock.elapsedRealtime() - captureStarted) + " nominal_ms=" + clock.time()
					+ " image_bytes=" + (card.getAllocationByteCount() + scene.getAllocationByteCount()));
			// Transfer last: any preceding failure must leave the live return clock's callback intact.
			clock.transfer(active::frame);
			return true;
		} catch (RuntimeException | OutOfMemoryError e) {
			// If session/callback allocation failed after capture, do not retain a half-installed owner.
			active = null;
			if (e instanceof OutOfMemoryError) PreviewMemoryPolicy.reclaim(host.getContext(), "reply_overlap_out_of_memory");
			return false;
		} finally {
			// Before ownership transfers, a failed capture leaves the live swipe unchanged.
			if (active == null) {
				if (scene != null) scene.recycle();
				if (card != null) card.recycle();
			}
		}
	}

	private RectF bounds(View view) {
		int[] base = new int[2], child = new int[2];
		host.getLocationInWindow(base);
		view.getLocationInWindow(child);
		int x = child[0] - base[0], y = child[1] - base[1];
		return new RectF(x, y, x + view.getWidth(), y + view.getHeight());
	}

	void keepOnly(ContentFragment destination) {
		if (active != null && active.destination != destination) finish();
	}

	void install(ContentFragment destination) {
		if (active != null && active.destination == destination && !active.installed) {
			active.installed = true;
			host.getOverlay().add(active.layer);
		}
	}

	void bind(ContentFragment destination, View root) {
		if (active != null && active.destination == destination && active.installed) active.bind(root);
	}

	void release(ContentFragment destination) {
		if (active != null && active.destination == destination) finish();
	}

	void finish() {
		Session session = active;
		active = null;
		if (session != null) session.close();
	}
	@Override public void close() {
		if (closed) return;
		closed = true; finish(); memory.close();
	}

	private final class Session {
		final PostingFragment destination;
		final Clock clock;
		final float releaseX;
		final RectF sceneBounds, rowBounds, clip, frame = new RectF();
		final Bitmap scene, card;
		final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		boolean installed, ready;
		OneShotPreDrawListener preDraw;
		final Drawable layer = new BaseDrawable() {
			@Override public void draw(Canvas canvas) { drawFrame(canvas); }
		};

		Session(PostingFragment destination, Clock clock, float releaseX, Bitmap scene, RectF sceneBounds,
				Bitmap card, RectF rowBounds, RectF clip) {
			this.destination = destination; this.clock = clock; this.releaseX = releaseX;
			this.scene = scene; this.sceneBounds = sceneBounds; this.card = card; this.rowBounds = rowBounds;
			this.clip = clip;
			layer.setBounds(0, 0, host.getWidth(), host.getHeight());
		}

		void bind(View root) {
			if (preDraw != null) preDraw.removeListener();
			ready = false;
			preDraw = OneShotPreDrawListener.add(root, () -> {
				preDraw = null;
				if (active == this) {
					ready = true;
					layer.invalidateSelf();
				}
			});
		}

		void frame() {
			if (active != this) return;
			if (clock.time() >= ReplySwipeOverlapSpec.END_MS || InterfaceMotion.duration(1) == 0) finish();
			else if (installed) layer.invalidateSelf();
		}

		void drawFrame(Canvas canvas) {
			if (active != this) return;
			float time = clock.time();
			int alpha = ready ? Math.round(255f * (1f - ReplySwipeOverlapSpec.incomingAlpha(time))) : 255;
			if (alpha <= 0) return;
			int saved = alpha == 255 ? canvas.save()
					: canvas.saveLayerAlpha(sceneBounds.left, sceneBounds.top, sceneBounds.right, sceneBounds.bottom, alpha);
			canvas.clipRect(sceneBounds);
			canvas.drawBitmap(scene, null, sceneBounds, paint);
			canvas.clipRect(clip);
			frame.set(rowBounds);
			frame.offset(ReplySwipeOverlapSpec.offset(releaseX, time), 0f);
			canvas.drawBitmap(card, null, frame, paint);
			canvas.restoreToCount(saved);
		}

		void close() {
			clock.stop();
			if (preDraw != null) { preDraw.removeListener(); preDraw = null; }
			if (installed) host.getOverlay().remove(layer);
			// Once removed, let GC release the bitmaps after any submitted render frame finishes.
		}
	}
}
