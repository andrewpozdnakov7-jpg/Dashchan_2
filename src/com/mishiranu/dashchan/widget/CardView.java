package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import com.mishiranu.dashchan.util.ResourceUtils;

public class CardView extends FrameLayout {
	private final boolean initialized;

	public CardView(Context context) {
		this(context, null);
	}

	public CardView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public CardView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		float density = ResourceUtils.obtainDensity(context);
		float size = 1f * density + 0.5f;
		RoundRectDrawable backgroundDrawable = new RoundRectDrawable(backgroundColor, size);
		setBackgroundDrawable(backgroundDrawable);
		setClipToOutline(true);
		setElevation(size);
		backgroundDrawable.setPadding(size);
		float elevation = backgroundDrawable.getPadding();
		float radius = backgroundDrawable.getRadius();
		int hPadding = (int) Math.ceil(calculateHorizontalPadding(elevation, radius));
		int vPadding = (int) Math.ceil(calculateVerticalPadding(elevation, radius));
		setPadding(hPadding, vPadding, hPadding, vPadding);
		initialized = true;
	}

	private int backgroundColor;

	private void setBackgroundColorInternal(int color) {
		backgroundColor = color;
		if (initialized) {
			((RoundRectDrawable) getBackground()).setColor(color);
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	@Deprecated
	public void setBackgroundDrawable(Drawable background) {
		if (background instanceof ColorDrawable) {
			int color = ((ColorDrawable) background).getColor();
			setBackgroundColorInternal(color);
			return;
		}
		super.setBackgroundDrawable(background);
	}

	@Override
	public void setBackgroundColor(int color) {
		setBackgroundColorInternal(color);
	}

	public int getBackgroundColor() {
		return backgroundColor;
	}

	private final static double COS_45 = Math.cos(Math.toRadians(45));
	private final static float SHADOW_MULTIPLIER = 1.5f;

	public static float calculateVerticalPadding(float maxShadowSize, float cornerRadius) {
		return (float) (maxShadowSize * SHADOW_MULTIPLIER + (1 - COS_45) * cornerRadius);
	}

	public static float calculateHorizontalPadding(float maxShadowSize, float cornerRadius) {
		return (float) (maxShadowSize + (1 - COS_45) * cornerRadius);
	}

	private static class RoundRectDrawable extends BaseDrawable {
		private final float radius;
		private final Paint paint;
		private final RectF boundsF;
		private final Rect boundsI;
		private float padding;

		public RoundRectDrawable(int backgroundColor, float radius) {
			this.radius = radius;
			paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
			paint.setColor(backgroundColor);
			boundsF = new RectF();
			boundsI = new Rect();
		}

		public void setPadding(float padding) {
			if (padding == this.padding) {
				return;
			}
			this.padding = padding;
			updateBounds(null);
			invalidateSelf();
		}

		public float getPadding() {
			return padding;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			canvas.drawRoundRect(boundsF, radius, radius, paint);
		}

		private void updateBounds(Rect bounds) {
			if (bounds == null) {
				bounds = getBounds();
			}
			boundsF.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
			boundsI.set(bounds);
			float vInset = calculateVerticalPadding(padding, radius);
			float hInset = calculateHorizontalPadding(padding, radius);
			boundsI.inset((int) Math.ceil(hInset), (int) Math.ceil(vInset));
			boundsF.set(boundsI);
		}

		@Override
		protected void onBoundsChange(Rect bounds) {
			super.onBoundsChange(bounds);
			updateBounds(bounds);
		}

		@Override
		public void getOutline(Outline outline) {
			outline.setRoundRect(boundsI, radius);
		}

		public float getRadius() {
			return radius;
		}

		public void setColor(int color) {
			paint.setColor(color);
			invalidateSelf();
		}
	}

}
