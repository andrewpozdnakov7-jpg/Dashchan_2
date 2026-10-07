package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Matrix;
import android.graphics.RectF;

/** A view-space pose, never serialized or passed to export. Scale never crosses zero. */
public final class EditorPose {
    public float angle, frameAngle, scale, x, y, cropVisibility;
    public boolean mirror, frameReversed;
    // Rectangles in pixel-sized axes rotated by frameAngle around (x,y).
    public final RectF clip = new RectF(), crop = new RectF(), available = new RectF();

    public EditorPose copy() { EditorPose out = new EditorPose(); out.set(this); return out; }
    public void set(EditorPose other) {
        angle = other.angle; frameAngle = other.frameAngle; scale = other.scale;
        x = other.x; y = other.y; mirror = other.mirror; frameReversed = other.frameReversed;
        cropVisibility = other.cropVisibility;
        clip.set(other.clip); crop.set(other.crop); available.set(other.available);
    }
    public void offset(float dx, float dy) { x += dx; y += dy; available.offset(dx, dy); }
    public static float nearest(float angle, float reference) {
        return angle + 360f * Math.round((reference - angle) / 360f);
    }
    public void unwrapFrame(float next) {
        float fine = nearest(angle - frameAngle, 0);
        frameAngle = next; angle = next + fine;
    }
    /** Equivalent rectangle axes can differ by a half-turn; image orientation stays exact. */
    public void reframe(float next) {
        float difference = frameAngle - next;
        if (difference != 0) {
            Matrix axes = new Matrix(); axes.setRotate(difference); axes.mapRect(clip); axes.mapRect(crop);
            if ((Math.round(difference / 180f) & 1) != 0) frameReversed = !frameReversed;
        }
        frameAngle = next;
    }

    public static EditorPose capture(Matrix image, RectF clip, RectF crop, RectF available,
            boolean cropMode, int quarter, boolean mirror, int width, int height) {
        EditorPose out = new EditorPose(); float[] values = new float[9]; image.getValues(values);
        out.scale = (float) Math.hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]);
        out.mirror = mirror;
        out.angle = (float) Math.toDegrees(Math.atan2(values[Matrix.MSKEW_Y] * (mirror ? -1 : 1),
                values[Matrix.MSCALE_X] * (mirror ? -1 : 1)));
        float[] center = {width / 2f, height / 2f}; image.mapPoints(center); out.x = center[0]; out.y = center[1];
        out.frameAngle = quarter * (mirror ? -90f : 90f); out.cropVisibility = cropMode ? 1 : 0;
        Matrix inverse = new Matrix(); inverse.setTranslate(-out.x, -out.y); inverse.postRotate(-out.frameAngle);
        out.clip.set(clip); inverse.mapRect(out.clip); out.crop.set(crop); inverse.mapRect(out.crop);
        out.available.set(available); out.unwrapFrame(out.frameAngle); return out;
    }

    public void imageMatrix(Matrix out, int width, int height) {
        out.setTranslate(-width / 2f, -height / 2f);
        out.postScale(mirror ? -scale : scale, scale); out.postRotate(angle); out.postTranslate(x, y);
    }
    public void frameMatrix(Matrix out) { out.setRotate(frameAngle); out.postTranslate(x, y); }

    public static void interpolate(EditorPose from, EditorPose to, float progress, EditorPose out) {
        if (progress <= 0) { out.set(from); out.mirror = to.mirror; return; }
        if (progress >= 1) { out.set(to); return; }
        out.angle = mix(from.angle, to.angle, progress); out.frameAngle = mix(from.frameAngle, to.frameAngle, progress);
        out.scale = mix(from.scale, to.scale, progress); out.x = mix(from.x, to.x, progress); out.y = mix(from.y, to.y, progress);
        out.mirror = to.mirror; out.frameReversed = to.frameReversed;
        out.cropVisibility = mix(from.cropVisibility, to.cropVisibility, progress);
        rect(from.clip, to.clip, progress, out.clip); rect(from.crop, to.crop, progress, out.crop);
        rect(from.available, to.available, progress, out.available);
        // Keep one continuously interpolated frame. Switching between crop and full-image
        // bounds halfway through a panel transition would introduce a scale discontinuity.
        RectF bounds = out.clip;
        double a = Math.toRadians(out.frameAngle);
        float c = (float) Math.abs(Math.cos(a)), s = (float) Math.abs(Math.sin(a));
        float bw = c * bounds.width() + s * bounds.height(), bh = s * bounds.width() + c * bounds.height();
        float fit = Math.min(1, Math.min(out.available.width() / Math.max(.001f, bw), out.available.height() / Math.max(.001f, bh)));
        if (fit < 1) {
            out.scale *= fit; multiply(out.clip, fit); multiply(out.crop, fit);
            bw *= fit; bh *= fit;
        }
        float centerX = (float) (Math.cos(a) * bounds.centerX() - Math.sin(a) * bounds.centerY());
        float centerY = (float) (Math.sin(a) * bounds.centerX() + Math.cos(a) * bounds.centerY());
        out.x = clamp(out.x + centerX, out.available.left + bw / 2, out.available.right - bw / 2) - centerX;
        out.y = clamp(out.y + centerY, out.available.top + bh / 2, out.available.bottom - bh / 2) - centerY;
    }
    private static float clamp(float value, float minimum, float maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static void rect(RectF a, RectF b, float t, RectF out) {
        out.set(mix(a.left, b.left, t), mix(a.top, b.top, t), mix(a.right, b.right, t), mix(a.bottom, b.bottom, t));
    }
    private static void multiply(RectF rect, float factor) { rect.set(rect.left * factor, rect.top * factor, rect.right * factor, rect.bottom * factor); }
}
