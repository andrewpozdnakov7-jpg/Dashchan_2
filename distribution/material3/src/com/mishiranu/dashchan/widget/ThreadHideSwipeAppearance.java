package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.R;

/** Draw-only presentation. No touch target, data mutation, animation or global palette. */
public final class ThreadHideSwipeAppearance {
	public static final class Shape {
		public final RectF bounds;
		public final float radius;
		public Shape(RectF bounds, float radius) { this.bounds = new RectF(bounds); this.radius = radius; }
	}
	private final Context context;
	private final TextView label;
	private final Drawable icon;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	private AppearanceTypography.Configuration typography;

	public ThreadHideSwipeAppearance(Context context) {
		this(context, R.string.hide, R.drawable.ic_pe_hide);
	}
	/** Reuse the same tonal surface, typography and motion for other list actions. */
	public ThreadHideSwipeAppearance(Context context, int labelResId, int iconResId) {
		this.context = context; label = new TextView(context); label.setText(labelResId);
		icon = context.getDrawable(iconResId).mutate();
	}
	private float dp(float value) { return value * context.getResources().getDisplayMetrics().density; }
	public Shape shape(View view) {
		Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight()); float radius = 0;
		if (view.getOutlineProvider() != null) {
			Outline outline = new Outline(); view.getOutlineProvider().getOutline(view, outline);
			Rect actual = new Rect();
			if (outline.getRect(actual) && !actual.isEmpty()) { rect = actual; radius = Math.max(0, outline.getRadius()); }
		}
		rect.offset(view.getLeft(), view.getTop());
		return new Shape(new RectF(rect), radius);
	}
	private void clip(Canvas canvas, RecyclerView list, Shape shape) {
		canvas.clipRect(list.getPaddingLeft(), list.getPaddingTop(), list.getWidth() - list.getPaddingRight(),
				list.getHeight() - list.getPaddingBottom());
		path.reset(); path.addRoundRect(shape.bounds, shape.radius, shape.radius, Path.Direction.CW);
		canvas.clipPath(path);
	}
	public void draw(Canvas canvas, RecyclerView list, Shape shape, float dx, float progress) {
		if (dx == 0 || shape.bounds.isEmpty()) return;
		AppearanceColors colors = StandardAppearance.colors(context);
		AppearanceTypography.Configuration next = new AppearanceTypography.Configuration(context);
		if (!next.equals(typography)) { typography = next; AppearanceTypography.apply(label, AppearanceTypography.Role.LABEL_LARGE); }
		Paint text = label.getPaint(); text.setColor(colors.onPrimaryContainer); text.setTextAlign(Paint.Align.CENTER);
		float width = text.measureText(label.getText().toString()), size = dp(24), inset = dp(16);
		boolean showLabel = width + inset * 2 <= shape.bounds.width()
				&& size + dp(4) + label.getLineHeight() <= shape.bounds.height();
		float blockWidth = showLabel ? Math.max(size, width) : size;
		float cx = dx < 0 ? shape.bounds.right - inset - blockWidth / 2 : shape.bounds.left + inset + blockWidth / 2;
		float blockHeight = size + (showLabel ? dp(4) + label.getLineHeight() : 0);
		float top = shape.bounds.centerY() - blockHeight / 2;
		int save = canvas.save(); clip(canvas, list, shape);
		if (dx < 0) canvas.clipRect(Math.max(shape.bounds.left, shape.bounds.right + dx), shape.bounds.top,
				shape.bounds.right, shape.bounds.bottom);
		else canvas.clipRect(shape.bounds.left, shape.bounds.top, Math.min(shape.bounds.right, shape.bounds.left + dx), shape.bounds.bottom);
		fill.setColor(colors.primaryContainer); canvas.drawRect(shape.bounds, fill);
		int contentAlpha = Math.round(255 * Math.min(1, Math.abs(dx) / (inset + size)));
		icon.setTint(colors.onPrimaryContainer); icon.setAlpha(contentAlpha);
		float scale = .88f + .12f * Math.max(0, Math.min(progress, 1));
		int iconSave = canvas.save(); canvas.scale(scale, scale, cx, top + size / 2);
		icon.setBounds(Math.round(cx - size / 2), Math.round(top), Math.round(cx + size / 2), Math.round(top + size));
		icon.draw(canvas); canvas.restoreToCount(iconSave);
		if (showLabel) {
			Paint.FontMetrics metrics = text.getFontMetrics(); text.setAlpha(contentAlpha);
			float lineTop = top + size + dp(4);
			canvas.drawText(label.getText().toString(), cx,
					lineTop + (label.getLineHeight() - metrics.bottom + metrics.top) / 2 - metrics.top, text);
		}
		canvas.restoreToCount(save);
	}
	/** A shrinking tonal surface bridges the actual old/new layouts without changing item heights. */
	public void drawReflow(Canvas canvas, RecyclerView list, Shape from, Shape to, float progress) {
		float p = Math.max(0, Math.min(progress, 1));
		RectF a = from.bounds, b = to.bounds;
		Shape current = new Shape(new RectF(a.left + (b.left - a.left) * p, a.top + (b.top - a.top) * p,
				a.right + (b.right - a.right) * p, a.bottom + (b.bottom - a.bottom) * p), from.radius + (to.radius - from.radius) * p);
		if (current.bounds.isEmpty()) return;
		int save = canvas.save(); clip(canvas, list, current);
		fill.setColor(StandardAppearance.colors(context).primaryContainer); fill.setAlpha(Math.round(255 * (1 - p)));
		canvas.drawRect(current.bounds, fill); canvas.restoreToCount(save);
	}
}
