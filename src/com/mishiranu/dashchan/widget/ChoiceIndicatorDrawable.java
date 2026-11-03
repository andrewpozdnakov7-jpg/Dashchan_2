package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.mishiranu.dashchan.util.OpaqueColors;
import com.mishiranu.dashchan.util.ResourceUtils;
import java.util.Arrays;
import java.util.function.Supplier;

/** Shared 24dp geometry for checkbox, mixed checkbox and exclusive radio selection. */
public final class ChoiceIndicatorDrawable extends Drawable {
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private final int size;
	private final boolean radio;
	private int[] colors;
	private int alpha = 255;
	private boolean mixed;
	private Supplier<int[]> stateColors;

	public ChoiceIndicatorDrawable(View owner, boolean radio) {
		size = Math.round(24 * ResourceUtils.obtainDensity(owner)); this.radio = radio;
	}
	public boolean isRadio() { return radio; }
	public void setStateColors(Supplier<int[]> source) { stateColors = source; }
	/** outline, selected fill, on-selected glyph, actual surface, ripple. */
	public void setColors(int[] next) {
		if (!Arrays.equals(colors, next)) { colors = next.clone(); invalidateSelf(); }
	}
	public void setMixed(boolean value) { if (mixed != value) { mixed = value; invalidateSelf(); } }
	@Override public int getIntrinsicWidth() { return size; }
	@Override public int getIntrinsicHeight() { return size; }
	@Override public boolean isStateful() { return true; }
	@Override protected boolean onStateChange(int[] state) {
		if (stateColors != null) setColors(stateColors.get());
		invalidateSelf(); return true;
	}
	@Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
	// A one-color host tint cannot represent both the fill and its contrasting check.
	// In particular, native TextView compound tints must not replace this palette.
	@Override public void setTintList(ColorStateList tint) {}
	@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
	@SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	@Override public void draw(Canvas canvas) {
		if (colors == null) return;
		boolean checked = mixed, enabled = false;
		for (int state : getState()) {
			if (state == android.R.attr.state_checked) checked = true;
			if (state == android.R.attr.state_enabled) enabled = true;
		}
		Rect bounds = getBounds(); int save = canvas.save();
		canvas.translate(bounds.left, bounds.top); canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
		int color = colors[checked ? 1 : 0];
		paint.setColor(enabled ? color : OpaqueColors.blend(colors[3], color, .38f)); paint.setAlpha(alpha);
		paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
		if (radio) {
			paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(12, 12, 9, paint);
			if (checked) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 5, paint); }
		} else {
			paint.setStyle(checked ? Paint.Style.FILL : Paint.Style.STROKE);
			canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
			if (checked) {
				paint.setColor(enabled ? colors[2] : colors[3]); paint.setAlpha(alpha); paint.setStyle(Paint.Style.STROKE);
				if (mixed) canvas.drawLine(7, 12, 17, 12, paint);
				else {
					path.reset(); path.moveTo(7, 12); path.lineTo(10.5f, 15.5f); path.lineTo(17, 9);
					canvas.drawPath(path, paint);
				}
			}
		}
		canvas.restoreToCount(save);
	}
}
