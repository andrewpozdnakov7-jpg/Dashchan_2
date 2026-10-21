package com.mishiranu.dashchan.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

/** Original 24-unit outline artwork for the drawer, not extracted reference-image assets. */
public final class DrawerGlyph extends Drawable {
	public enum Kind {EYE, EYE_FILLED, PAUSE, ERROR, BLOCK, SYNC, TAG, GROUP, CHAT, HISTORY, ARCHIVE, SETTINGS, MORE, CHEVRON}
	private final Kind kind;
	private final int intrinsicSize;
	private final float strokeWidth;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path path = new Path();
	public DrawerGlyph(Kind kind, int color) { this(kind, color, 24); }
	public DrawerGlyph(Kind kind, int color, int intrinsicSize) {
		this(kind, color, intrinsicSize, 1.8f);
	}
	public DrawerGlyph(Kind kind, int color, int intrinsicSize, float strokeWidth) {
		this.kind = kind; this.intrinsicSize = intrinsicSize; this.strokeWidth = strokeWidth; paint.setColor(color);
	}
	public void setColor(int color) { paint.setColor(color); invalidateSelf(); }
	@Override public void draw(@NonNull Canvas canvas) {
		canvas.save();
		canvas.translate(getBounds().left, getBounds().top);
		canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
		paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(strokeWidth);
		paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
		path.reset();
		switch (kind) {
			case EYE: case EYE_FILLED:
				path.moveTo(2, 12); path.cubicTo(7, 3, 17, 3, 22, 12); path.cubicTo(17, 21, 7, 21, 2, 12); path.close();
				if (kind == Kind.EYE_FILLED) {
					path.addCircle(12, 12, 4.4f, Path.Direction.CW); path.setFillType(Path.FillType.EVEN_ODD);
					paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); canvas.drawCircle(12, 12, 2.1f, paint);
				} else { canvas.drawPath(path, paint); canvas.drawCircle(12, 12, 3.3f, paint); }
				break;
			case PAUSE: case ERROR: case BLOCK:
				canvas.drawCircle(12, 12, 9, paint);
				if (kind == Kind.PAUSE) { canvas.drawLine(9, 8, 9, 16, paint); canvas.drawLine(15, 8, 15, 16, paint); }
				else if (kind == Kind.BLOCK) canvas.drawLine(6, 18, 18, 6, paint);
				else { canvas.drawLine(12, 7, 12, 12, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 16, 1, paint); }
				break;
			case SYNC:
				canvas.drawArc(4, 4, 20, 20, 30, 130, false, paint); canvas.drawArc(4, 4, 20, 20, 210, 130, false, paint);
				path.moveTo(2, 12); path.lineTo(4, 16); path.lineTo(8, 14); path.moveTo(22, 12); path.lineTo(20, 8); path.lineTo(16, 10); canvas.drawPath(path, paint); break;
			case TAG:
				path.moveTo(3, 4); path.lineTo(11, 4); path.lineTo(21, 14); path.lineTo(13, 22); path.lineTo(3, 12); path.close(); canvas.drawPath(path, paint); canvas.drawCircle(7, 8, 1, paint); break;
			case GROUP:
				canvas.drawCircle(9, 7, 3, paint); canvas.drawCircle(18, 8, 2.5f, paint);
				canvas.drawRoundRect(2, 14, 16, 21, 3, 3, paint); path.moveTo(18, 14); path.quadTo(23, 14, 22, 21); canvas.drawPath(path, paint); break;
			case CHAT:
				path.moveTo(3, 4); path.lineTo(21, 4); path.lineTo(21, 17); path.lineTo(8, 17); path.lineTo(3, 22); path.close(); canvas.drawPath(path, paint); break;
			case HISTORY:
				canvas.drawArc(4, 3, 22, 21, 215, 300, false, paint); path.moveTo(2, 5); path.lineTo(2, 11); path.lineTo(8, 11); path.moveTo(13, 7); path.lineTo(13, 12); path.lineTo(17, 14); canvas.drawPath(path, paint); break;
			case ARCHIVE:
				canvas.drawRoundRect(3, 7, 21, 21, 2, 2, paint); canvas.drawRoundRect(2, 3, 22, 7, 1, 1, paint);
				path.moveTo(12, 10); path.lineTo(12, 17); path.moveTo(8, 14); path.lineTo(12, 18); path.lineTo(16, 14); canvas.drawPath(path, paint); break;
			case SETTINGS:
				for (int i = 0; i < 16; i++) { double angle = Math.PI * i / 8; float radius = i % 2 == 0 ? 10 : 8; float x = 12 + radius * (float) Math.sin(angle), y = 12 + radius * (float) Math.cos(angle); if (i == 0) path.moveTo(x, y); else path.lineTo(x, y); }
				path.close(); canvas.drawPath(path, paint); canvas.drawCircle(12, 12, 3, paint); break;
			case MORE:
				paint.setStyle(Paint.Style.FILL); for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 1.7f, paint); break;
			case CHEVRON:
				path.moveTo(5, 9); path.lineTo(12, 16); path.lineTo(19, 9); canvas.drawPath(path, paint); break;
		}
		canvas.restore();
	}
	@Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
	@Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
	@SuppressWarnings("deprecation")
	@Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
	@Override public int getIntrinsicWidth() { return intrinsicSize; }
	@Override public int getIntrinsicHeight() { return intrinsicSize; }
}
