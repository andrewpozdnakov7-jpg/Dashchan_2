package com.mishiranu.dashchan.ui.posting;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.ResourceUtils;

/** Decoration only: its parent surface owns playback and native long press. */
final class AttachmentVideoMarker extends View {
	private final ImageView image;
	private final Drawable badge;
	private final RectF bounds = new RectF();
	private final View.OnLayoutChangeListener imageLayout = (v, l, t, r, b, ol, ot, or, ob) -> invalidate();

	AttachmentVideoMarker(Context context, ImageView image) {
		super(context); this.image = image;
		badge = context.getDrawable(R.drawable.slooop_attachment_video_play_badge).mutate();
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		setClickable(false); setLongClickable(false); setFocusable(false);
		setVisibility(INVISIBLE); setWillNotDraw(false);
	}
	void imageBounds(RectF out) {
		out.set(0, 0, getWidth(), getHeight());
		Drawable drawable = image.getDrawable();
		if (image.getVisibility() != VISIBLE || drawable == null || drawable.getBounds().isEmpty()) return;
		out.set(drawable.getBounds()); image.getImageMatrix().mapRect(out);
		out.offset(image.getLeft() + image.getPaddingLeft(), image.getTop() + image.getPaddingTop());
		if (!out.intersect(0, 0, getWidth(), getHeight())) out.set(0, 0, getWidth(), getHeight());
	}
	@Override protected void onDraw(Canvas canvas) {
		super.onDraw(canvas); imageBounds(bounds);
		float density = ResourceUtils.obtainDensity(this), inset = 2 * density;
		float width = 16 * density, height = 14 * density;
		// Degenerate thumbnail aspect ratios must not put the sign outside the visible frame.
		float scale = Math.min(1f, Math.min(Math.max(0, bounds.width() - 2 * inset) / width,
				Math.max(0, bounds.height() - 2 * inset) / height));
		if (scale <= 0) return;
		int right = Math.round(bounds.right - inset), bottom = Math.round(bounds.bottom - inset);
		badge.setBounds(Math.round(right - width * scale), Math.round(bottom - height * scale), right, bottom);
		badge.draw(canvas);
	}
	@Override protected void onAttachedToWindow() {
		super.onAttachedToWindow(); image.addOnLayoutChangeListener(imageLayout); invalidate();
	}
	@Override protected void onDetachedFromWindow() {
		image.removeOnLayoutChangeListener(imageLayout); super.onDetachedFromWindow();
	}
}
