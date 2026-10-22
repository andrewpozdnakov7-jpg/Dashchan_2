package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;

/** One region transform for rendering, hit testing, handles and screen-aligned creation. */
public final class EditorMaskGeometry {
    private EditorMaskGeometry() {}
    public static boolean isMask(EditorDocument.Kind kind) {
        return kind == EditorDocument.Kind.BLUR || kind == EditorDocument.Kind.MOSAIC || kind == EditorDocument.Kind.COVER;
    }
    public static RectF localBounds(EditorDocument.Item item, int width, int height) {
        float side = Math.max(width, height);
        float w = item.orientedMask ? item.maskWidth * side : Math.abs(item.endX - item.x) * width;
        float h = item.orientedMask ? item.maskHeight * side : Math.abs(item.endY - item.y) * height;
        return new RectF(-w / 2, -h / 2, w / 2, h / 2);
    }
    public static float[] center(EditorDocument.Item item, int width, int height) {
        return item.orientedMask ? new float[] {item.x * width, item.y * height}
                : new float[] {(item.x + item.endX) * width / 2, (item.y + item.endY) * height / 2};
    }
    public static Matrix matrix(EditorDocument.Item item, int width, int height) {
        Matrix matrix = new Matrix(); float[] center = center(item, width, height);
        if (item.orientedMask) {
            matrix.postScale(item.mirrored ? -item.scale : item.scale, item.scale);
            matrix.postRotate(item.angle);
        }
        matrix.postTranslate(center[0], center[1]); return matrix;
    }
    public static Path path(EditorDocument.Item item, int width, int height) {
        Path path = new Path(); path.addRect(localBounds(item, width, height), Path.Direction.CW);
        path.transform(matrix(item, width, height)); return path;
    }
    /** Call inside the edit transaction only: legacy region stays visually source-aligned. */
    public static void makeEditable(EditorDocument.Item item, int width, int height) {
        if (item.orientedMask) return;
        RectF bounds = localBounds(item, width, height); float[] center = center(item, width, height);
        float side = Math.max(width, height);
        item.x = item.endX = center[0] / width; item.y = item.endY = center[1] / height;
        item.maskWidth = Math.max(1, bounds.width()) / side; item.maskHeight = Math.max(1, bounds.height()) / side;
        item.angle = 0; item.scale = 1; item.mirrored = false; item.orientedMask = true;
    }
    /** Inverse viewport basis cancels quarter-turn, fine rotation, mirror, crop zoom and pan. */
    public static void fromScreenDrag(EditorDocument.Item item, int width, int height, Matrix viewToSource,
            float startX, float startY, float endX, float endY) {
        float[] center = {(startX + endX) / 2, (startY + endY) / 2}; viewToSource.mapPoints(center);
        float[] basis = {1, 0, 0, 1}; viewToSource.mapVectors(basis);
        float side = Math.max(width, height);
        item.x = item.endX = Math.max(0, Math.min(1, center[0] / width));
        item.y = item.endY = Math.max(0, Math.min(1, center[1] / height));
        item.maskWidth = Math.abs(endX - startX) * (float) Math.hypot(basis[0], basis[1]) / side;
        item.maskHeight = Math.abs(endY - startY) * (float) Math.hypot(basis[2], basis[3]) / side;
        item.mirrored = basis[0] * basis[3] - basis[1] * basis[2] < 0;
        float sign = item.mirrored ? -1 : 1;
        item.angle = (float) Math.toDegrees(Math.atan2(sign * basis[1], sign * basis[0]));
        item.scale = 1; item.orientedMask = true;
    }
}
