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

/** Static-photo-only renderer shared by both flavors; does not touch a player or zoom matrix. */
final class PredictivePhotoPresentation implements PredictiveTransition.Presentation, Runnable {
	private Runnable finishSession;
	void setFinishSession(Runnable finishSession) { this.finishSession = finishSession; }
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
	private final RectF image, frame = new RectF();
	private final Drawable background;
	private final int backgroundAlpha, accessibility, width, height;
	private final long photoGeneration;
	private final int photoWidth, photoHeight;
	private final float photoAlpha, chromeAlpha;
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
		this.root = root; this.photo = photo; this.chrome = chrome; this.target = target;
		this.grid = grid; this.ownerValid = ownerValid;
		gridLeased = com.mishiranu.dashchan.widget.BackPreviewBudget.retain(grid);
		image = photo.getGalleryImageBounds(); background = root.getBackground();
		photoGeneration = photo.getGalleryImageGeneration();
		photoWidth = photo.getWidth(); photoHeight = photo.getHeight();
		backgroundAlpha = background != null ? background.getAlpha() : 255;
		photoAlpha = photo.getAlpha(); chromeAlpha = chrome != null ? chrome.getAlpha() : 1f;
		width = root.getWidth(); height = root.getHeight(); accessibility = root.getImportantForAccessibility();
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
	@Override public boolean valid() {
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
			PhotoTransitionGeometry.interpolate(frame, image, target.frame, value);
			photo.setGalleryFrame(this, frame, value > 0f && target.crop);
		} else photo.setAlpha(photoAlpha * (1f - value));
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
		gridLayerHost.getOverlay().remove(gridLayer); photo.clearGalleryFrame(this);
		if (gridLeased) com.mishiranu.dashchan.widget.BackPreviewBudget.release(grid);
		if (photo.getTag(R.id.gallery_motion_owner) == this) { photo.setTag(R.id.gallery_motion_owner, null); photo.setAlpha(photoAlpha); }
		if (root.getTag(R.id.gallery_motion_owner) == this) {
			root.setTag(R.id.gallery_motion_owner, null); root.setImportantForAccessibility(accessibility);
			if (background != null && root.getBackground() == background) background.setAlpha(backgroundAlpha);
			if (chrome != null) chrome.setAlpha(chromeAlpha);
		}
	}
}
