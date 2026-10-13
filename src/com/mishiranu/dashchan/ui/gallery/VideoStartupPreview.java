package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.media.VideoFrameDiagnostics;
import com.mishiranu.dashchan.media.VideoPlayer;
import com.mishiranu.dashchan.widget.PhotoView;
import java.util.function.BooleanSupplier;

/** Keeps the cached thumbnail visible until the new texture has delivered a frame. */
final class VideoStartupPreview extends BaseDrawable implements View.OnAttachStateChangeListener,
		View.OnLayoutChangeListener {
	private final ViewGroup parent;
	private final View video;
	private final VideoPlayer player;
	private final BooleanSupplier current;
	private final Window window;
	private final Geometry startupGeometry;
	private final Runnable onReady;
	private final long started = SystemClock.elapsedRealtime();
	private final Runnable frameCallback = this::onFrame;
	private Drawable thumbnail;
	private boolean closed;
	private final Rect diagnosticClip = new Rect();
	private int drawCalls;
	private int paintedDraws;
	private int drawStateReports;
	private int layoutReports;
	private String lastDrawState;
	private long firstPaintMs = -1L;
	private long lastPaintMs = -1L;

	VideoStartupPreview(ViewGroup parent, View video, VideoPlayer player, Drawable thumbnail, Geometry startupGeometry, Window window,
			BooleanSupplier current, Runnable onReady) {
		this.parent = parent;
		this.video = video;
		this.player = player;
		this.thumbnail = thumbnail;
		this.current = current;
		this.window = window;
		this.startupGeometry = startupGeometry;
		this.onReady = onReady;
		setBounds(0, 0, parent.getWidth(), parent.getHeight());
		// An overlay does not add a child, change layout, intercept touches or replace the surface.
		// Attach it to the parent: TextureView itself does not support drawable rendering.
		parent.getOverlay().add(this);
		parent.addOnAttachStateChangeListener(this);
		parent.addOnLayoutChangeListener(this);
		player.setVideoViewFrameCallback(frameCallback);
		VideoDiagnostics.recordUi("gallery startup_preview_waiting");
		record("attached", " captured_geometry=" + (startupGeometry != null));
	}

	/** Immutable geometry captured before PhotoView is cleared; it never owns the cached bitmap. */
	static final class Geometry {
		private final RectF imageBounds;
		private final RectF viewport;
		private final Matrix localToParent;
		private final float alpha;

		Geometry(RectF imageBounds, RectF viewport, Matrix localToParent, float alpha) {
			this.imageBounds = new RectF(imageBounds);
			this.viewport = new RectF(viewport);
			this.localToParent = new Matrix(localToParent);
			this.alpha = alpha;
		}

		boolean draw(Canvas canvas, Drawable thumbnail, Rect diagnosticClip) {
			int saved = canvas.save();
			try {
				canvas.concat(localToParent);
				boolean visible = canvas.clipRect(viewport) && canvas.clipRect(imageBounds);
				if (diagnosticClip != null) canvas.getClipBounds(diagnosticClip);
				canvas.saveLayerAlpha(imageBounds, Math.round(255f * alpha));
				canvas.drawColor(Color.BLACK);
				int width = Math.max(1, thumbnail.getIntrinsicWidth());
				int height = Math.max(1, thumbnail.getIntrinsicHeight());
				canvas.translate(imageBounds.left, imageBounds.top);
				canvas.scale(imageBounds.width() / width, imageBounds.height() / height);
				thumbnail.setBounds(0, 0, width, height);
				thumbnail.draw(canvas);
				return visible;
			} finally {
				canvas.restoreToCount(saved);
			}
		}
	}

	static Geometry captureGeometry(PhotoView photo, ViewGroup parent) {
		RectF bounds = new RectF();
		return photo.getImageDisplayRect(bounds) != null && !bounds.isEmpty()
				? geometryFromView(photo, parent, bounds) : null;
	}

	private static Geometry geometryFromView(View source, ViewGroup parent, RectF imageBounds) {
		if (source.getWidth() <= 0 || source.getHeight() <= 0) return null;
		Matrix matrix = new Matrix();
		source.transformMatrixToGlobal(matrix);
		parent.transformMatrixToLocal(matrix);
		RectF viewport = new RectF(0, 0, source.getWidth(), source.getHeight());
		Rect clip = source.getClipBounds();
		if (clip != null && !viewport.intersect(new RectF(clip))) return null;
		return new Geometry(imageBounds, viewport, matrix, source.getAlpha());
	}

	static Geometry resolveGeometry(ViewGroup parent, View video, Geometry captured, Drawable thumbnail) {
		if (video.getWidth() > 0 && video.getHeight() > 0) {
			return geometryFromView(video, parent, new RectF(0, 0, video.getWidth(), video.getHeight()));
		}
		if (captured != null) return captured;
		// First-open layouts may not have a measured PhotoView either. The existing
		// cached thumbnail still has dimensions, so fit it inside the measured viewport.
		int width = parent.getWidth();
		int height = parent.getHeight();
		int imageWidth = thumbnail.getIntrinsicWidth();
		int imageHeight = thumbnail.getIntrinsicHeight();
		if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null;
		float scale = Math.min((float) width / imageWidth, (float) height / imageHeight);
		float fittedWidth = imageWidth * scale;
		float fittedHeight = imageHeight * scale;
		float left = (width - fittedWidth) / 2f;
		float top = (height - fittedHeight) / 2f;
		return new Geometry(new RectF(left, top, left + fittedWidth, top + fittedHeight),
				new RectF(0, 0, width, height), new Matrix(), video.getAlpha());
	}

	private void onFrame() {
		if (closed) return;
		boolean currentPlayer = current.getAsBoolean();
		if (!currentPlayer || !parent.isAttachedToWindow()) {
			close(currentPlayer ? "frame_parent_detached" : "frame_stale_player");
			return;
		}
		// VideoPlayer now posts this on the UI thread only after a decoder buffer has been
		// latched and the containing frame submitted. Do not remove it during TextureView.draw.
		VideoDiagnostics.recordUi("gallery startup_preview_first_frame elapsed_ms="
				+ (SystemClock.elapsedRealtime() - started) + " readiness=buffer_committed");
		close("buffer_committed");
		VideoFrameDiagnostics.recordWindowAfterCommit(window, video, "preview_removed", current, () -> closed);
		parent.invalidate();
		if (onReady != null) onReady.run();
	}

	void close() {
		close("cleared");
	}

	private void close(String reason) {
		if (closed) return;
		record("removed", " reason=" + reason + " draw_calls=" + drawCalls
				+ " painted_draws=" + paintedDraws + " first_paint_ms=" + firstPaintMs
				+ " last_paint_ms=" + lastPaintMs);
		if (VideoDiagnostics.isExtendedRecording()) {
			VideoDiagnostics.recordViewGeometry("startup_preview_removed", video);
		}
		closed = true;
		player.clearVideoViewFrameCallback(frameCallback);
		parent.removeOnAttachStateChangeListener(this);
		parent.removeOnLayoutChangeListener(this);
		parent.getOverlay().remove(this);
		// This is a borrowed cache drawable; never recycle its shared bitmap.
		thumbnail = null;
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		drawCalls++;
		String skipped = closed ? "closed" : thumbnail == null ? "no_thumbnail"
				: video.getParent() != parent ? "wrong_parent" : !video.isShown() ? "video_not_shown" : null;
		if (skipped != null) {
			recordDrawState("skipped_" + skipped, "");
			return;
		}
		Geometry geometry = resolveGeometry(parent, video, startupGeometry, thumbnail);
		if (geometry == null) {
			recordDrawState("skipped_no_geometry", "");
			return;
		}
		String geometrySource = video.getWidth() > 0 && video.getHeight() > 0 ? "video"
				: startupGeometry != null ? "captured_photo" : "fitted_thumbnail";
		boolean diagnostics = VideoDiagnostics.isExtendedRecording();
		boolean nonEmptyClip = geometry.draw(canvas, thumbnail, diagnostics ? diagnosticClip : null);
		paintedDraws++;
		lastPaintMs = SystemClock.elapsedRealtime() - started;
		if (firstPaintMs < 0L) firstPaintMs = lastPaintMs;
		if (diagnostics) {
			String state = nonEmptyClip ? "paint_submitted" : "empty_clip";
			if (drawStateReports < 8 && (drawCalls <= 4 || !state.equals(lastDrawState))) {
				recordDrawState(state, " geometry=" + geometrySource + " canvas_clip=" + diagnosticClip.toShortString()
						+ " canvas_hardware=" + canvas.isHardwareAccelerated());
			}
			if (paintedDraws == 1) VideoDiagnostics.recordViewGeometry("startup_preview_first_paint", video);
			if (paintedDraws == 1) {
				VideoFrameDiagnostics.recordWindowAfterCommit(window, video, "preview_first_paint", current, () -> closed);
			}
		}
	}

	private void recordDrawState(String state, String details) {
		if (!VideoDiagnostics.isExtendedRecording() || drawStateReports >= 8) return;
		// First paints and state changes only; no per-frame logging throughout playback.
		if (state.equals(lastDrawState) && drawCalls > 4) return;
		lastDrawState = state;
		drawStateReports++;
		record("draw", " state=" + state + " draw_calls=" + drawCalls
				+ " painted_draws=" + paintedDraws + details);
	}

	private void record(String event, String details) {
		if (!VideoDiagnostics.isExtendedRecording()) return;
		VideoDiagnostics.recordUi("gallery startup_preview_trace schema=1 preview=" + System.identityHashCode(this)
				+ " texture=" + System.identityHashCode(video) + " parent=" + System.identityHashCode(parent)
				+ " event=" + event + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
				+ " parent_size=" + parent.getWidth() + "x" + parent.getHeight()
				+ " video_size=" + video.getWidth() + "x" + video.getHeight()
				+ " parent_alpha=" + parent.getAlpha() + " video_alpha=" + video.getAlpha()
				+ " video_shown=" + video.isShown() + " attached=" + parent.isAttachedToWindow()
				+ " overlay_bounds=" + getBounds().toShortString() + details);
	}

	@Override public void onViewAttachedToWindow(View view) {}
	@Override public void onViewDetachedFromWindow(View view) { close("parent_detached"); }
	@Override public void onLayoutChange(View view, int l, int t, int r, int b,
			int oldL, int oldT, int oldR, int oldB) {
		setBounds(0, 0, parent.getWidth(), parent.getHeight());
		if (VideoDiagnostics.isExtendedRecording() && layoutReports < 8) {
			layoutReports++;
			record("parent_layout", "");
		}
		invalidateSelf();
	}
}
