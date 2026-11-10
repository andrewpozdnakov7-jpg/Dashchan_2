package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PhotoView;
import com.mishiranu.dashchan.widget.PredictiveTransition;
import java.util.function.BooleanSupplier;

/** Live-media renderer shared by both flavors; never changes a player or its zoom matrix. */
final class PredictivePhotoPresentation implements PredictiveTransition.Presentation, PhotoView.GalleryImageReplacementOwner {
	private Runnable finishSession;
	private Runnable restoredCallback;
	void setFinishSession(Runnable finishSession) { this.finishSession = finishSession; }
	void setRestoredCallback(Runnable callback) { restoredCallback = callback; }
	@Override public void run() { if (finishSession != null) finishSession.run(); else restore(); }
	static class Target extends PhotoTransitionGeometry.Target {
		Target(AttachmentView target, PhotoView photo) { super(target, photo); }
	}
	private final ViewGroup root;
	private final PhotoView photo;
	private final View chrome;
	private final Target target;
	private final Bitmap grid;
	private final boolean gridLeased;
	private boolean restored;
	private final View gridLayerHost;
	private final BooleanSupplier ownerValid;
	private RectF image;
	private final RectF frame = new RectF();
	private RectF replacementFrame;
	private final RectF replacementTarget = new RectF();
	private float replacementFraction;
	private final Object item;
	private final Drawable background;
	private final int backgroundAlpha, accessibility, width, height;
	private long photoGeneration;
	private final int photoWidth, photoHeight;
	private final float photoAlpha, chromeAlpha;
	private GalleryMotionController.PresentationFrame surface;
	private GalleryMotionController.PresentationFrame[] controls;
	private int surfaceWidth, surfaceHeight, surfaceLeft, surfaceTop;
	private BooleanSupplier surfaceCurrent;
	private final RectF surfaceImage = new RectF(), surfaceFrame = new RectF();
	private float fraction;
	private final BaseDrawable gridLayer = new BaseDrawable() {
		final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		@Override public void draw(Canvas canvas) {
			if (grid != null && !grid.isRecycled()) {
				paint.setAlpha(Math.round(255 * fraction)); canvas.drawBitmap(grid, null, getBounds(), paint);
			}
		}
	};
	PredictivePhotoPresentation(ViewGroup root, PhotoView photo, View chrome, Target target,
			Bitmap grid, Rect gridBounds, BooleanSupplier ownerValid) {
		this(root, photo, chrome, target, grid, gridBounds, ownerValid, null, new View[0]);
	}
	PredictivePhotoPresentation(ViewGroup root, PhotoView photo, View chrome, Target target,
			Bitmap grid, Rect gridBounds, BooleanSupplier ownerValid, View videoSurface, View[] videoControls) {
		this(root, photo, chrome, target, grid, gridBounds, ownerValid, videoSurface, videoControls, null);
	}
	PredictivePhotoPresentation(ViewGroup root, PhotoView photo, View chrome, Target target,
			Bitmap grid, Rect gridBounds, BooleanSupplier ownerValid, View videoSurface, View[] mediaControls, Object item) {
		this.root = root; this.photo = photo; this.chrome = chrome; this.target = target;
		this.item = item;
		this.grid = grid; this.ownerValid = ownerValid;
		gridLeased = com.mishiranu.dashchan.widget.BackPreviewBudget.retain(grid);
		image = photo.getGalleryImageBounds(); background = root.getBackground();
		if (image == null && !photo.hasImage()) image = new RectF(0, 0, photo.getWidth(), photo.getHeight());
		photoGeneration = photo.getGalleryImageGeneration();
		photoWidth = photo.getWidth(); photoHeight = photo.getHeight();
		backgroundAlpha = background != null ? background.getAlpha() : 255;
		photoAlpha = photo.getAlpha(); chromeAlpha = chrome != null ? chrome.getAlpha() : 1f;
		width = root.getWidth(); height = root.getHeight(); accessibility = root.getImportantForAccessibility();
		surface = videoSurface != null ? new GalleryMotionController.PresentationFrame(videoSurface) : null;
		surfaceWidth = videoSurface != null ? videoSurface.getWidth() : 0;
		surfaceHeight = videoSurface != null ? videoSurface.getHeight() : 0;
		surfaceLeft = videoSurface != null ? videoSurface.getLeft() : 0;
		surfaceTop = videoSurface != null ? videoSurface.getTop() : 0;
		java.util.ArrayList<GalleryMotionController.PresentationFrame> frames = new java.util.ArrayList<>();
		for (View control : mediaControls) {
			if (control != null && control != chrome && control != root && control != photo && control != videoSurface
					&& frames.stream().noneMatch(f -> f.view == control)) {
				frames.add(new GalleryMotionController.PresentationFrame(control));
			}
		}
		controls = frames.toArray(new GalleryMotionController.PresentationFrame[0]);
		if (surface != null) surface.own(this);
		for (GalleryMotionController.PresentationFrame control : controls) control.own(this);
		root.setTag(R.id.gallery_motion_owner, this); photo.setTag(R.id.gallery_motion_owner, this);
		// No temporary interactive View is added; keep the live hierarchy's accessibility focus.
		// PhotoView draws its framework overlay before its image. A targeted grid must be
		// below that image; a root overlay would paint over the shrinking photograph.
		gridLayerHost = grid != null && target != null ? photo : root;
		if (grid != null) {
			Rect bounds = new Rect(gridBounds);
			if (gridLayerHost == photo) {
				int[] from = new int[2], to = new int[2];
				root.getLocationOnScreen(from); photo.getLocationOnScreen(to);
				bounds.offset(from[0] - to[0], from[1] - to[1]);
			}
			gridLayer.setBounds(bounds); gridLayerHost.getOverlay().add(gridLayer);
		}
	}
	@Override public boolean canReplaceImage(Object replacementItem) {
		// Keep the exact item's thumbnail, animated drawable or video placeholder in one session.
		return !restored && surface == null && item != null && item == replacementItem && valid();
	}
	void startFromOpening(GalleryMotionController.OpeningFrame opening) {
		if (target == null || opening.visible == null || opening.fraction <= 0f || opening.fraction >= 1f) return;
		replacementFrame = new RectF(opening.visible);
		replacementFraction = opening.fraction;
		replacementTarget.set(target.frame);
		if (!target.crop) fitFrame(replacementTarget, image, target.frame);
	}
	float getPhotoAlphaForHandoff() { return photoAlpha; }
	void attachVideoSurface(View view, View[] mediaControls, BooleanSupplier current) {
		if (restored) return;
		if (!valid() || view == null || view.getWidth() <= 0 || view.getHeight() <= 0
				|| surface != null || view.getTag(R.id.gallery_motion_owner) != null) { run(); return; }
		java.util.ArrayList<GalleryMotionController.PresentationFrame> frames = new java.util.ArrayList<>(java.util.Arrays.asList(controls));
		for (View control : mediaControls) {
			if (control != null && control != chrome && control != root && control != photo && control != view
					&& frames.stream().noneMatch(f -> f.view == control)) {
				if (control.getTag(R.id.gallery_motion_owner) != null) { run(); return; }
				frames.add(new GalleryMotionController.PresentationFrame(control));
			}
		}
		surface = new GalleryMotionController.PresentationFrame(view);
		surfaceWidth = view.getWidth(); surfaceHeight = view.getHeight();
		surfaceLeft = view.getLeft(); surfaceTop = view.getTop(); surfaceCurrent = current;
		controls = frames.toArray(new GalleryMotionController.PresentationFrame[0]);
		surface.own(this);
		for (GalleryMotionController.PresentationFrame control : controls) control.own(this);
		apply(fraction);
	}
	@Override public void onImageReplaced() {
		if (restored) return;
		RectF next = photo.getGalleryImageBounds();
		if (next == null) { run(); return; }
		// Keep the visible rectangle at the current finger position, even when decoded
		// dimensions differ from the thumbnail metadata. Reversal still reaches the new rest bounds.
		RectF visible = target != null && fraction > 0f && fraction < 1f && !frame.isEmpty()
				? new RectF(frame) : null;
		if (visible != null && replacementFrame == null && !target.crop) fitFrame(visible, image, frame);
		replacementFrame = visible;
		replacementFraction = fraction;
		image = next;
		if (target != null) {
			replacementTarget.set(target.frame);
			if (!target.crop) fitFrame(replacementTarget, image, target.frame);
		}
		photoGeneration = photo.getGalleryImageGeneration();
		apply(fraction);
	}
	private static void fitFrame(RectF out, RectF image, RectF bounds) {
		float scale = Math.min(bounds.width() / image.width(), bounds.height() / image.height());
		float halfWidth = image.width() * scale / 2f, halfHeight = image.height() * scale / 2f;
		out.set(bounds.centerX() - halfWidth, bounds.centerY() - halfHeight,
				bounds.centerX() + halfWidth, bounds.centerY() + halfHeight);
	}
	@Override public boolean valid() {
		if (surfaceCurrent != null && !surfaceCurrent.getAsBoolean()) return false;
		if (surface != null && (!surface.view.isAttachedToWindow()
				|| surface.view.getTag(R.id.gallery_motion_owner) != this
				|| surface.view.getWidth() != surfaceWidth || surface.view.getHeight() != surfaceHeight
				|| surface.view.getLeft() != surfaceLeft || surface.view.getTop() != surfaceTop)) return false;
		for (GalleryMotionController.PresentationFrame control : controls) {
			if (control.view.getTag(R.id.gallery_motion_owner) != this) return false;
		}
		return ownerValid.getAsBoolean() && root.isAttachedToWindow() && photo.isAttachedToWindow()
				&& root.getWidth() == width && root.getHeight() == height && image != null
				&& photo.getGalleryImageGeneration() == photoGeneration
				&& photo.getWidth() == photoWidth && photo.getHeight() == photoHeight
				&& root.getTag(R.id.gallery_motion_owner) == this && photo.getTag(R.id.gallery_motion_owner) == this
				&& (target == null || target.valid(photo)) && (grid == null || !grid.isRecycled());
	}
	@Override public void apply(float value) {
		fraction = value;
		if (target != null && image != null) {
			if (replacementFrame != null && value < replacementFraction) {
				PhotoTransitionGeometry.interpolate(frame, image, replacementFrame, value / replacementFraction);
			} else if (replacementFrame != null) {
				PhotoTransitionGeometry.interpolate(frame, replacementFrame, replacementTarget,
						(value - replacementFraction) / (1f - replacementFraction));
			} else PhotoTransitionGeometry.interpolate(frame, image, target.frame, value);
			photo.setGalleryFrame(this, frame, value > 0f && (target.crop || replacementFrame != null));
		} else photo.setAlpha(photoAlpha * (1f - value));
		if (surface != null) {
			if (target != null && image != null) {
				surfaceImage.set(image); surfaceFrame.set(frame);
				float dx = photo.getLeft() - surfaceLeft, dy = photo.getTop() - surfaceTop;
				surfaceImage.offset(dx, dy); surfaceFrame.offset(dx, dy);
				// Transform only the parent. Texture, decoder, orientation and user zoom stay intact.
				surface.frame(this, surfaceImage, surfaceFrame, value > 0f && (target.crop || replacementFrame != null));
			} else surface.fade(this, 1f - value);
		}
		for (GalleryMotionController.PresentationFrame control : controls) control.fade(this, 1f - value);
		if (grid == null) {
			if (background != null && root.getBackground() == background) background.setAlpha(Math.round(backgroundAlpha * (1f - value)));
			if (chrome != null) chrome.setAlpha(chromeAlpha * (1f - value));
		}
		gridLayer.invalidateSelf();
	}
	static float lerp(float from, float to, float value) { return from + (to - from) * value; }
	@Override public void restore() {
		if (restored) return; restored = true;
		finishSession = null;
		surfaceCurrent = null;
		gridLayerHost.getOverlay().remove(gridLayer); photo.clearGalleryFrame(this);
		if (surface != null) surface.release(this);
		for (GalleryMotionController.PresentationFrame control : controls) control.release(this);
		if (gridLeased) com.mishiranu.dashchan.widget.BackPreviewBudget.release(grid);
		if (photo.getTag(R.id.gallery_motion_owner) == this) { photo.setTag(R.id.gallery_motion_owner, null); photo.setAlpha(photoAlpha); }
		if (root.getTag(R.id.gallery_motion_owner) == this) {
			root.setTag(R.id.gallery_motion_owner, null); root.setImportantForAccessibility(accessibility);
			if (background != null && root.getBackground() == background) background.setAlpha(backgroundAlpha);
			if (chrome != null) chrome.setAlpha(chromeAlpha);
		}
		Runnable callback = restoredCallback; restoredCallback = null;
		if (callback != null) callback.run();
	}
}
