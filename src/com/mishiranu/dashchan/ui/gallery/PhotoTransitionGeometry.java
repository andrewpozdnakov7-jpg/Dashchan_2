package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import com.mishiranu.dashchan.widget.AttachmentView;
import com.mishiranu.dashchan.widget.PhotoView;
import java.lang.ref.WeakReference;

/** Shared photo/thumbnail coordinates for finger-driven Back and timed gallery transitions. */
final class PhotoTransitionGeometry {
	private PhotoTransitionGeometry() {}
	static void interpolate(RectF out, RectF from, RectF to, float value) {
		out.set(lerp(from.left, to.left, value), lerp(from.top, to.top, value),
				lerp(from.right, to.right, value), lerp(from.bottom, to.bottom, value));
	}
	static float lerp(float from, float to, float value) { return from + (to - from) * value; }
	static class Target {
		final WeakReference<AttachmentView> view;
		final View root;
		final long binding;
		final Rect visible = new Rect(), expected = new Rect();
		final int[] location = new int[2], photoLocation = new int[2];
		final RectF frame;
		final boolean crop;
		final Matrix globalMatrix = new Matrix();
		final float[] matrixValues = new float[9];
		Target(AttachmentView target, PhotoView photo) {
			view = new WeakReference<>(target); root = target.getRootView(); binding = target.getImageBindingGeneration();
			target.getGlobalVisibleRect(expected); target.getLocationOnScreen(location); photo.getLocationOnScreen(photoLocation);
			frame = new RectF(location[0] - photoLocation[0], location[1] - photoLocation[1],
					location[0] - photoLocation[0] + target.getWidth(), location[1] - photoLocation[1] + target.getHeight());
			crop = target.isCropEnabled();
		}
		boolean valid(PhotoView photo) {
			globalMatrix.reset(); photo.transformMatrixToGlobal(globalMatrix); globalMatrix.getValues(matrixValues);
			if (Math.abs(matrixValues[Matrix.MSCALE_X] - 1f) > .001f || Math.abs(matrixValues[Matrix.MSCALE_Y] - 1f) > .001f
					|| Math.abs(matrixValues[Matrix.MSKEW_X]) > .001f || Math.abs(matrixValues[Matrix.MSKEW_Y]) > .001f) return false;
			AttachmentView target = view.get();
			if (target == null || !target.isAttachedToWindow() || !target.isShown() || target.getRootView() != root
					|| target.getImageBindingGeneration() != binding || target.isCropEnabled() != crop
					|| !target.getGlobalVisibleRect(visible) || !visible.equals(expected)
					|| visible.width() != target.getWidth() || visible.height() != target.getHeight()) return false;
			target.getLocationOnScreen(location); photo.getLocationOnScreen(photoLocation);
			return frame.left == location[0] - photoLocation[0] && frame.top == location[1] - photoLocation[1]
					&& frame.width() == target.getWidth() && frame.height() == target.getHeight();
		}
	}
}
