package com.mishiranu.dashchan.media;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import java.util.function.BooleanSupplier;

/** Optional asynchronous GPU readback. Never drives frame readiness or playback. */
public final class VideoFrameDiagnostics {
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final int MAX_PENDING = 4;
	// Called on the UI thread; completion and admission share the same thread.
	private static int pending;

	private VideoFrameDiagnostics() {}

	public static void recordSurface(SurfaceTexture surfaceTexture, View video, long generation, int update,
			BooleanSupplier current) {
		long capture = VideoDiagnostics.getExtendedCaptureId();
		if (capture == 0L || !current.getAsBoolean()) return;
		String tag = "source=producer_surface texture=" + System.identityHashCode(video)
				+ " generation=" + generation + " trigger_update=" + update + " ownership=diagnostic";
		if (surfaceTexture == null || surfaceTexture.isReleased()) {
			record(tag, "status=surface_texture_unavailable");
			return;
		}
		Surface surface;
		try {
			tag += " trigger_timestamp_ns=" + surfaceTexture.getTimestamp();
			// The player's temporary Java Surface is released after native acknowledges it.
			// Own a separate wrapper of the same producer; never change playback ownership.
			surface = new Surface(surfaceTexture);
		} catch (RuntimeException | OutOfMemoryError e) {
			record(tag, "status=surface_wrapper_failed error=" + e.getClass().getSimpleName());
			return;
		}
		if (!surface.isValid()) {
			surface.release();
			record(tag, "status=surface_unavailable");
			return;
		}
		// current retains/checks the SurfaceTexture identity until asynchronous completion.
		// copy owns this wrapper even on admission/allocation/request failure.
		copy(capture, tag, current, (bitmap, listener) -> PixelCopy.request(surface, bitmap, listener, MAIN),
				surface::release);
	}

	/** Observe an already-requested frame, without invalidating or delaying the handoff. */
	public static void recordWindowAfterCommit(Window window, View video, String phase,
			BooleanSupplier current, BooleanSupplier previewClosed) {
		long capture = VideoDiagnostics.getExtendedCaptureId();
		if (capture == 0L || !current.getAsBoolean() || !video.isAttachedToWindow()) return;
		ViewTreeObserver observer = video.getViewTreeObserver();
		if (!observer.isAlive() || !video.isHardwareAccelerated()) return;
		class Probe implements Runnable, View.OnAttachStateChangeListener {
			private boolean cancelled;
			@Override public void run() {
				MAIN.post(() -> {
					video.removeOnAttachStateChangeListener(this);
					if (!cancelled && capture == VideoDiagnostics.getExtendedCaptureId()
							&& current.getAsBoolean() && video.isAttachedToWindow()) {
						recordWindow(window, video, phase, capture, current, previewClosed.getAsBoolean());
					}
				});
			}
			@Override public void onViewAttachedToWindow(View view) {}
			@Override public void onViewDetachedFromWindow(View view) {
				cancelled = true;
				video.removeOnAttachStateChangeListener(this);
				if (observer.isAlive()) observer.unregisterFrameCommitCallback(this);
				if (capture == VideoDiagnostics.getExtendedCaptureId()) {
					record("source=window texture=" + System.identityHashCode(video) + " phase=" + phase,
							"status=observer_cancelled reason=detached");
				}
			}
		}
		Probe probe = new Probe();
		try {
			video.addOnAttachStateChangeListener(probe);
			observer.registerFrameCommitCallback(probe);
		} catch (RuntimeException e) {
			video.removeOnAttachStateChangeListener(probe);
			record("source=window texture=" + System.identityHashCode(video) + " phase=" + phase,
					"status=observer_unavailable error=" + e.getClass().getSimpleName());
		}
	}

	private static void recordWindow(Window window, View video, String phase, long capture,
			BooleanSupplier current, boolean previewClosed) {
		String tag = "source=window texture=" + System.identityHashCode(video) + " phase=" + phase
				+ " preview_closed_at_request=" + previewClosed;
		View decor = window != null ? window.peekDecorView() : null;
		if (decor == null || video.getRootView() != decor.getRootView() || video.getWidth() <= 0
				|| video.getHeight() <= 0 || !decor.isAttachedToWindow()) {
			record(tag, "status=window_unavailable");
			return;
		}
		try {
			// Local -> decor -> window coordinates, including view/ancestor transforms.
			Matrix matrix = new Matrix();
			video.transformMatrixToGlobal(matrix);
			decor.transformMatrixToLocal(matrix);
			RectF bounds = new RectF(0, 0, video.getWidth(), video.getHeight());
			matrix.mapRect(bounds);
			if (!bounds.intersect(0, 0, decor.getWidth(), decor.getHeight())) {
				record(tag, "status=outside_window");
				return;
			}
			int[] location = new int[2];
			decor.getLocationInWindow(location);
			bounds.offset(location[0], location[1]);
			Rect rect = new Rect();
			bounds.roundOut(rect);
			String details = tag + " crop=" + rect.toShortString() + " video_alpha=" + video.getAlpha();
			copy(capture, details, () -> current.getAsBoolean() && video.isAttachedToWindow()
					&& video.getRootView() == decor.getRootView(),
					(bitmap, listener) -> PixelCopy.request(window, rect, bitmap, listener, MAIN));
		} catch (RuntimeException | OutOfMemoryError e) {
			record(tag, "status=unavailable error=" + e.getClass().getSimpleName());
		}
	}

	private interface Request {
		void run(Bitmap bitmap, PixelCopy.OnPixelCopyFinishedListener listener);
	}

	private static void copy(long capture, String tag, BooleanSupplier current, Request request) {
		copy(capture, tag, current, request, null);
	}

	private static void copy(long capture, String tag, BooleanSupplier current, Request request,
			Runnable cleanup) {
		if (pending >= MAX_PENDING) {
			if (cleanup != null) cleanup.run();
			record(tag, "status=busy");
			return;
		}
		Bitmap bitmap;
		try {
			bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
		} catch (RuntimeException | OutOfMemoryError e) {
			if (cleanup != null) cleanup.run();
			record(tag, "status=allocation_failed error=" + e.getClass().getSimpleName());
			return;
		}
		long requestedAt = SystemClock.elapsedRealtime();
		pending++;
		record(tag, "status=requested requested_elapsed_ms=" + requestedAt);
		try {
			request.run(bitmap, result -> {
				pending--;
				try {
					if (capture != VideoDiagnostics.getExtendedCaptureId()) return;
					String timing = " requested_elapsed_ms=" + requestedAt + " copy_latency_ms="
							+ (SystemClock.elapsedRealtime() - requestedAt);
					if (!current.getAsBoolean()) {
						record(tag, "status=stale" + timing);
					} else if (result != PixelCopy.SUCCESS) {
						record(tag, "status=copy_error result=" + result + timing);
					} else {
						int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
						try {
							bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
							record(tag, "status=ok" + timing + " " + VideoFrameStatistics.describe(pixels));
						} finally {
							java.util.Arrays.fill(pixels, 0);
						}
					}
				} catch (RuntimeException | OutOfMemoryError e) {
					record(tag, "status=unavailable error=" + e.getClass().getSimpleName());
				} finally {
					// Never encode, persist, share, or retain the temporary pixels.
					try {
						try { bitmap.eraseColor(0); } catch (RuntimeException ignored) {}
						finally { bitmap.recycle(); }
					} finally {
						if (cleanup != null) cleanup.run();
					}
				}
			});
		} catch (RuntimeException | OutOfMemoryError e) {
			pending--;
			try { bitmap.recycle(); } finally { if (cleanup != null) cleanup.run(); }
			record(tag, "status=request_failed error=" + e.getClass().getSimpleName());
			return;
		}
		// Do not enter the request-failure cleanup after a successfully queued copy.
		record(tag, "status=dispatched request_call_ms=" + (SystemClock.elapsedRealtime() - requestedAt));
	}

	private static void record(String tag, String details) {
		VideoDiagnostics.recordUi("startup_pixels schema=2 " + tag + " " + details);
	}
}
