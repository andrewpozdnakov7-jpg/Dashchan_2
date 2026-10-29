package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PhotoView;
import java.lang.ref.WeakReference;

/** Geometry of the same settled frame as the grid bitmap. Never reveals or scrolls the live grid. */
final class PredictiveGridGeometry {
	static final class Cell {
		final WeakReference<AttachmentView> thumbnail;
		final Rect bounds;
		final long binding;
		final boolean crop;
		Cell(AttachmentView thumbnail, Rect bounds) {
			this.thumbnail = new WeakReference<>(thumbnail); this.bounds = new Rect(bounds);
			binding = thumbnail.getImageBindingGeneration(); crop = thumbnail.isCropEnabled();
		}
	}
	private final ViewGroup grid, root;
	private final Rect bounds = new Rect(), check = new Rect();
	private final int width, height, rootWidth, rootHeight, scrollX, scrollY;
	private final int[] rootLocation = new int[2], hostLocation = new int[2];

	PredictiveGridGeometry(ViewGroup grid) {
		this.grid = grid;
		root = (ViewGroup) grid.getRootView();
		width = grid.getWidth(); height = grid.getHeight();
		rootWidth = root.getWidth(); rootHeight = root.getHeight();
		scrollX = grid.getScrollX(); scrollY = grid.getScrollY();
		bounds.set(0, 0, width, height); root.offsetDescendantRectToMyCoords(grid, bounds);
	}

	Cell capture(AttachmentView thumbnail) {
		if (!valid() || !grid.isShown() || !grid.getMatrix().isIdentity()
				|| !thumbnail.isShown() || !insideGrid(thumbnail)) return null;
		if (!thumbnail.getGlobalVisibleRect(check) || check.width() != thumbnail.getWidth()
				|| check.height() != thumbnail.getHeight()) return null;
		check.set(0, 0, thumbnail.getWidth(), thumbnail.getHeight());
		grid.offsetDescendantRectToMyCoords(thumbnail, check);
		return new Cell(thumbnail, check);
	}

	private boolean insideGrid(View thumbnail) {
		for (View view = thumbnail; view != grid;) {
			if (view.getVisibility() != View.VISIBLE || !view.getMatrix().isIdentity()
					|| !(view.getParent() instanceof View)) return false;
			view = (View) view.getParent();
		}
		return true;
	}

	boolean valid() {
		if (!grid.isAttachedToWindow() || grid.getRootView() != root
				|| grid.getWidth() != width || grid.getHeight() != height
				|| root.getWidth() != rootWidth || root.getHeight() != rootHeight
				|| grid.getScrollX() != scrollX || grid.getScrollY() != scrollY) return false;
		// Ignore only the grid's own hiding transform. The bitmap represents its settled frame.
		for (View parent = grid.getParent() instanceof View ? (View) grid.getParent() : null;
				parent != null; parent = parent.getParent() instanceof View ? (View) parent.getParent() : null) {
			if (!parent.getMatrix().isIdentity()) return false;
		}
		check.set(0, 0, width, height); root.offsetDescendantRectToMyCoords(grid, check);
		return bounds.equals(check);
	}

	boolean cellValid(Cell cell) {
		AttachmentView thumbnail = cell.thumbnail.get();
		if (!valid() || thumbnail == null || !thumbnail.isAttachedToWindow() || !insideGrid(thumbnail)
				|| thumbnail.getImageBindingGeneration() != cell.binding || thumbnail.isCropEnabled() != cell.crop) return false;
		check.set(0, 0, thumbnail.getWidth(), thumbnail.getHeight());
		grid.offsetDescendantRectToMyCoords(thumbnail, check);
		return check.equals(cell.bounds);
	}

	void boundsIn(View host, Rect out) {
		root.getLocationOnScreen(rootLocation); host.getLocationOnScreen(hostLocation);
		out.set(bounds); out.offset(rootLocation[0] - hostLocation[0], rootLocation[1] - hostLocation[1]);
	}

	PredictivePhotoPresentation.Target target(Cell cell, PhotoView photo) {
		return cell != null && cellValid(cell) ? new Target(cell, photo) : null;
	}

	private final class Target extends PredictivePhotoPresentation.Target {
		private final Cell cell;
		private final PhotoView owner;
		private final Rect viewport = new Rect();
		Target(Cell cell, PhotoView photo) {
			super(cell.thumbnail.get(), photo); this.cell = cell; owner = photo;
			boundsIn(photo, viewport);
			frame.set(viewport.left + cell.bounds.left, viewport.top + cell.bounds.top,
					viewport.left + cell.bounds.right, viewport.top + cell.bounds.bottom);
		}
		@Override boolean valid(PhotoView photo) {
			if (photo != owner || !photo.isAttachedToWindow() || photo.getRootView() != root || !cellValid(cell)) return false;
			globalMatrix.reset(); photo.transformMatrixToGlobal(globalMatrix); globalMatrix.getValues(matrixValues);
			if (Math.abs(matrixValues[Matrix.MSCALE_X] - 1f) > .001f || Math.abs(matrixValues[Matrix.MSCALE_Y] - 1f) > .001f
					|| Math.abs(matrixValues[Matrix.MSKEW_X]) > .001f || Math.abs(matrixValues[Matrix.MSKEW_Y]) > .001f) return false;
			boundsIn(photo, viewport);
			return frame.left == viewport.left + cell.bounds.left && frame.top == viewport.top + cell.bounds.top
					&& frame.width() == cell.bounds.width() && frame.height() == cell.bounds.height();
		}
	}
}
