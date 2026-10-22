package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Matrix;
import android.graphics.RectF;

/** Shared preview/export geometry. Straightening zooms the source to cover the entire oriented frame. */
public final class EditorGeometry {
    private EditorGeometry() {}

    public static float width(EditorDocument.State state, int width, int height) {
        return (state.quarterTurns & 1) == 0 ? width : height;
    }

    public static float height(EditorDocument.State state, int width, int height) {
        return (state.quarterTurns & 1) == 0 ? height : width;
    }

    public static Matrix sourceToFrame(EditorDocument.State state, int width, int height) {
        float w = width(state, width, height), h = height(state, width, height);
        double radians = Math.toRadians(state.straighten);
        float c = (float) Math.abs(Math.cos(radians)), s = (float) Math.abs(Math.sin(radians));
        float cover = Math.max(c + s * h / w, c + s * w / h);
        Matrix matrix = new Matrix();
        matrix.postTranslate(-width / 2f, -height / 2f);
        matrix.postRotate(state.quarterTurns * 90f);
        if (state.mirror) matrix.postScale(-1f, 1f);
        matrix.postRotate(state.straighten);
        matrix.postScale(cover, cover);
        matrix.postTranslate(w / 2f, h / 2f);
        return matrix;
    }

    public static RectF cropPixels(EditorDocument.State state, int width, int height) {
        float w = width(state, width, height), h = height(state, width, height);
        return new RectF(state.crop.left * w, state.crop.top * h, state.crop.right * w, state.crop.bottom * h);
    }

    public static void setAspect(EditorDocument.State state, float aspect, int width, int height) {
        state.aspect = aspect;
        if (aspect <= 0) return;
        float w = width(state, width, height), h = height(state, width, height);
        float normalizedRatio = aspect * h / w;
        float cw = state.crop.width(), ch = state.crop.height();
        if (cw / ch > normalizedRatio) cw = ch * normalizedRatio;
        else ch = cw / normalizedRatio;
        setCenteredCrop(state, state.crop.centerX(), state.crop.centerY(), cw, ch);
    }

    public static void setCenteredCrop(EditorDocument.State state, float cx, float cy, float w, float h) {
        w = Math.max(0.00001f, Math.min(1f, w)); h = Math.max(0.00001f, Math.min(1f, h));
        cx = Math.max(w / 2f, Math.min(1f - w / 2f, cx));
        cy = Math.max(h / 2f, Math.min(1f - h / 2f, cy));
        state.crop.set(Math.max(0, cx - w / 2f), Math.max(0, cy - h / 2f),
                Math.min(1, cx + w / 2f), Math.min(1, cy + h / 2f));
    }

    /** Pinch moves the photo beneath a fixed selection, not an export-only viewport transform. */
    public static void pinchCrop(EditorDocument.State state, RectF start, float scale,
            float focusX, float focusY, float dx, float dy) {
        float minimumScale = Math.max(start.width(), start.height());
        float maximumScale = Math.min(start.width(), start.height()) / .00001f;
        scale = Math.max(minimumScale, Math.min(maximumScale, scale));
        setCenteredCrop(state, focusX + (start.centerX() - focusX - dx) / scale,
                focusY + (start.centerY() - focusY - dy) / scale,
                start.width() / scale, start.height() / scale);
    }

    /** Handle bits: left=1, right=2, top=4, bottom=8. Opposite corner/edge is the anchor. */
    public static void resizeCrop(EditorDocument.State state, RectF start, int edges,
            float dx, float dy, int width, int height) {
        boolean left = (edges & 1) != 0, right = (edges & 2) != 0;
        boolean top = (edges & 4) != 0, bottom = (edges & 8) != 0;
        if (!left && !right && !top && !bottom) return;
        if (state.aspect <= 0) {
            state.crop.set(start);
            if (left) state.crop.left = Math.max(0, Math.min(start.right - .00001f, start.left + dx));
            if (right) state.crop.right = Math.min(1, Math.max(start.left + .00001f, start.right + dx));
            if (top) state.crop.top = Math.max(0, Math.min(start.bottom - .00001f, start.top + dy));
            if (bottom) state.crop.bottom = Math.min(1, Math.max(start.top + .00001f, start.bottom + dy));
            return;
        }
        float w = width(state, width, height), h = height(state, width, height);
        float ratio = state.aspect * h / w;
        float ax = left ? start.right : right ? start.left : start.centerX();
        float ay = top ? start.bottom : bottom ? start.top : start.centerY();
        float availableW = left ? ax : right ? 1 - ax : 2 * Math.min(ax, 1 - ax);
        float availableH = top ? ay : bottom ? 1 - ay : 2 * Math.min(ay, 1 - ay);
        float wantedW = start.width() + (left ? -dx : right ? dx : 0);
        float wantedH = start.height() + (top ? -dy : bottom ? dy : 0);
        float ch;
        if ((left || right) && (top || bottom)) {
            // Project the pointer onto the aspect-constrained diagonal in image pixel space.
            double rw = ratio * (double) w;
            ch = (float) ((rw * w * wantedW + (double) h * h * wantedH) / (rw * rw + (double) h * h));
        } else ch = left || right ? wantedW / ratio : wantedH;
        float maximum = Math.min(availableH, availableW / ratio);
        float minimum = Math.min(maximum, Math.max(.00001f, .00001f / ratio));
        ch = Math.max(minimum, Math.min(maximum, ch));
        float cw = ch * ratio;
        float x = left ? ax - cw : right ? ax : ax - cw / 2;
        float y = top ? ay - ch : bottom ? ay : ay - ch / 2;
        // Clamp roundoff only; size was already bounded while retaining the anchor.
        state.crop.set(Math.max(0, x), Math.max(0, y), Math.min(1, x + cw), Math.min(1, y + ch));
    }

    public static void rotateQuarter(EditorDocument.State state) {
        RectF r = new RectF(state.crop);
        state.crop.set(1f - r.bottom, r.left, 1f - r.top, r.right);
        state.quarterTurns = (state.quarterTurns + (state.mirror ? 3 : 1)) & 3;
        if (state.aspect > 0) state.aspect = 1f / state.aspect;
        // R(90) R(fine) H R(quarter) = R(fine) H R(quarter - 90).
    }

    public static void mirror(EditorDocument.State state) {
        RectF crop = new RectF(state.crop);
        state.crop.set(1 - crop.right, crop.top, 1 - crop.left, crop.bottom);
        state.mirror = !state.mirror; state.straighten = -state.straighten;
    }
}
