package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

/** Weighted premultiplied RGBA addition, not two translucent SRC_OVER passes. */
public final class EditorPreviewBlend {
    private final Paint fromPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint toPaint = new Paint();
    private final RectF layerBounds = new RectF(), fromBounds = new RectF();
    public EditorPreviewBlend() { toPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); }
    public static void sourceOrientation(Matrix out, float fromAngle, boolean fromMirror,
            float toAngle, boolean toMirror, int width, int height) {
        float cx = width / 2f, cy = height / 2f;
        out.setTranslate(-cx, -cy); out.postScale(fromMirror ? -1 : 1, 1);
        out.postRotate(fromAngle - toAngle); out.postScale(toMirror ? -1 : 1, 1); out.postTranslate(cx, cy);
    }
    public void draw(Canvas canvas, Bitmap from, Matrix fromTransform, float fraction, int width, int height, Runnable next) {
        if (from == null || fraction >= 1) { next.run(); return; }
        if (fraction <= 0) {
            fromPaint.setAlpha(255); canvas.drawBitmap(from, fromTransform, fromPaint); return;
        }
        fromBounds.set(0, 0, from.getWidth(), from.getHeight()); fromTransform.mapRect(fromBounds);
        layerBounds.set(0, 0, width, height); layerBounds.union(fromBounds);
        int layer = canvas.saveLayer(layerBounds, null);
        fromPaint.setAlpha(Math.round(255 * (1 - fraction)));
        canvas.drawBitmap(from, fromTransform, fromPaint);
        if (fraction > 0) {
            toPaint.setAlpha(Math.round(255 * fraction));
            int target = canvas.saveLayer(layerBounds, toPaint);
            next.run(); canvas.restoreToCount(target);
        }
        canvas.restoreToCount(layer);
    }
}
