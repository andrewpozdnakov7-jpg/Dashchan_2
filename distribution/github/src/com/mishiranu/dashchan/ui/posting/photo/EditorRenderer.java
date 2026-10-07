package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** CPU-only processing. Invoke preparePhoto/export on a worker; preview and export share the same recipe. */
public final class EditorRenderer {
    private EditorRenderer() {}
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

    public static Bitmap preparePhoto(Bitmap source, EditorDocument.State state) {
        return preparePhoto(source, state, () -> false);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Obsolete photo preview");
    }

    public static Bitmap preparePhoto(Bitmap source, EditorDocument.State state, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        float brightness = state.adjustments[0] * 1.2f;
        float contrast = (float) Math.pow(2, state.adjustments[1] / 100f);
        float saturation = 1f + state.adjustments[2] / 100f;
        float warmth = state.adjustments[3] * 0.35f;
        float shadows = state.adjustments[4] * 0.8f, highlights = state.adjustments[5] * 0.8f;
        float strength = state.filterStrength / 100f;
        for (int i = 0; i < pixels.length; i++) {
            if ((i & 4095) == 0) checkCancelled(cancelled);
            int color = pixels[i];
            float r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            float luminance = (r * .2126f + g * .7152f + b * .0722f) / 255f;
            float tone = brightness + shadows * (1 - luminance) * (1 - luminance)
                    + highlights * luminance * luminance;
            r = (r - 127.5f) * contrast + 127.5f + tone + warmth;
            g = (g - 127.5f) * contrast + 127.5f + tone;
            b = (b - 127.5f) * contrast + 127.5f + tone - warmth;
            float gray = .2126f * r + .7152f * g + .0722f * b;
            r = gray + (r - gray) * saturation;
            g = gray + (g - gray) * saturation;
            b = gray + (b - gray) * saturation;
            float fr = r, fg = g, fb = b;
            switch (state.filter) {
                case MONO: fr = fg = fb = gray; break;
                case SEPIA:
                    fr = .393f * r + .769f * g + .189f * b;
                    fg = .349f * r + .686f * g + .168f * b;
                    fb = .272f * r + .534f * g + .131f * b; break;
                case WARM: fr += 22; fg += 5; fb -= 18; break;
                case COOL: fr -= 15; fg += 3; fb += 22; break;
                case VIVID:
                    fr = (gray + (r - gray) * 1.3f - 128) * 1.12f + 128;
                    fg = (gray + (g - gray) * 1.3f - 128) * 1.12f + 128;
                    fb = (gray + (b - gray) * 1.3f - 128) * 1.12f + 128; break;
                case FADE: fr = r * .8f + 35; fg = g * .8f + 32; fb = b * .8f + 30; break;
                default: break;
            }
            pixels[i] = Color.argb(Color.alpha(color), channel(r + (fr - r) * strength),
                    channel(g + (fg - g) * strength), channel(b + (fb - b) * strength));
        }
        if (state.adjustments[6] > 0) sharpen(pixels, width, height, state.adjustments[6] / 100f, cancelled);
        checkCancelled(cancelled);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            result.setPixels(pixels, 0, width, 0, 0, width, height);
            applyMasks(result, state, false, cancelled);
            checkCancelled(cancelled);
            return result;
        } catch (RuntimeException | OutOfMemoryError e) {
            result.recycle(); throw e;
        }
    }

    /** Worker composition is required for scene masks and whole-picture erasers.
     * Method name is retained for existing preview/export callers. */
    public static boolean hasSceneMasks(EditorDocument.State state) {
        for (EditorDocument.Item item : state.items) {
            if (item.kind == EditorDocument.Kind.ERASER && item.drawOnComposition) return true;
            if (item.affectAnnotations && (item.kind == EditorDocument.Kind.BLUR
                    || item.kind == EditorDocument.Kind.MOSAIC || item.kind == EditorDocument.Kind.COVER)) return true;
        }
        return false;
    }

    /** The same composition is used by worker preview and export; never flatten the document. */
    public static Bitmap prepareScene(Bitmap source, EditorDocument.State state, Map<String, Bitmap> assets,
            BooleanSupplier cancelled) {
        Bitmap result = preparePhoto(source, state, cancelled);
        try {
            checkCancelled(cancelled);
            Canvas scene = new Canvas(result);
            drawBaseAnnotations(scene, state, source.getWidth(), source.getHeight(), assets);
            // Top drawings, scene masks and whole erasers share their actual document order.
            // A new top stroke can therefore paint over an earlier erase or blur operation.
            Paint paint = shapePaint();
            for (int i = 0; i < state.items.size(); i++) {
                checkCancelled(cancelled);
                EditorDocument.Item item = state.items.get(i);
                if (isDrawing(item.kind) && item.drawOnComposition)
                    drawTopDrawing(scene, state, i, source.getWidth(), source.getHeight(), paint);
                else if (item.affectAnnotations && isMask(item.kind)) applyMask(result, item, cancelled);
            }
            checkCancelled(cancelled);
            return result;
        } catch (RuntimeException | OutOfMemoryError e) { result.recycle(); throw e; }
    }

    private static void sharpen(int[] pixels, int width, int height, float amount, BooleanSupplier cancelled) {
        // Two row buffers retain original neighbors without allocating a second full image.
        int[] previous = new int[width], current = new int[width], next = new int[width];
        System.arraycopy(pixels, 0, previous, 0, width);
        if (height < 3 || width < 3) return;
        System.arraycopy(pixels, width, current, 0, width);
        for (int y = 1; y < height - 1; y++) {
            checkCancelled(cancelled);
            System.arraycopy(pixels, (y + 1) * width, next, 0, width);
            for (int x = 1; x < width - 1; x++) {
                int c = current[x];
                float r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                float ar = (Color.red(previous[x]) + Color.red(next[x]) + Color.red(current[x - 1])
                        + Color.red(current[x + 1])) / 4f;
                float ag = (Color.green(previous[x]) + Color.green(next[x]) + Color.green(current[x - 1])
                        + Color.green(current[x + 1])) / 4f;
                float ab = (Color.blue(previous[x]) + Color.blue(next[x]) + Color.blue(current[x - 1])
                        + Color.blue(current[x + 1])) / 4f;
                pixels[y * width + x] = Color.argb(Color.alpha(c), channel(r + (r - ar) * amount),
                        channel(g + (g - ag) * amount), channel(b + (b - ab) * amount));
            }
            int[] swap = previous; previous = current; current = next; next = swap;
        }
    }

    private static Rect maskRect(EditorDocument.Item item, int width, int height) {
        return new Rect(Math.max(0, Math.round(Math.min(item.x, item.endX) * width)),
                Math.max(0, Math.round(Math.min(item.y, item.endY) * height)),
                Math.min(width, Math.round(Math.max(item.x, item.endX) * width)),
                Math.min(height, Math.round(Math.max(item.y, item.endY) * height)));
    }

    private static boolean isMask(EditorDocument.Kind kind) {
        return kind == EditorDocument.Kind.COVER || kind == EditorDocument.Kind.MOSAIC || kind == EditorDocument.Kind.BLUR;
    }

    private static void applyMasks(Bitmap photo, EditorDocument.State state, boolean annotations, BooleanSupplier cancelled) {
        for (EditorDocument.Item item : state.items) {
            checkCancelled(cancelled);
            if (isMask(item.kind) && item.affectAnnotations == annotations) applyMask(photo, item, cancelled);
        }
    }

    private static void applyMask(Bitmap photo, EditorDocument.Item item, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        Canvas canvas = new Canvas(photo);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int width = photo.getWidth(), height = photo.getHeight();
        Rect rect = maskRect(item, width, height);
        if (rect.width() < 1 || rect.height() < 1) return;
        if (item.kind == EditorDocument.Kind.COVER) {
            paint.setColor(item.color | 0xff000000); canvas.drawRect(rect, paint); return;
        }
        int cell = Math.max(2, Math.round(Math.min(width, height) * item.size));
        Bitmap region = null, small = null;
        try {
            region = Bitmap.createBitmap(photo, rect.left, rect.top, rect.width(), rect.height());
            small = Bitmap.createScaledBitmap(region, Math.max(1, rect.width() / cell),
                    Math.max(1, rect.height() / cell), true);
            if (item.kind == EditorDocument.Kind.BLUR) {
                Bitmap blurred = blurSmall(small, cancelled);
                if (small != region && small != photo) small.recycle();
                small = blurred;
            }
            paint.setFilterBitmap(item.kind == EditorDocument.Kind.BLUR);
            // Replace alpha as well; SRC_OVER leaves sharp content underneath a translucent blur.
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            canvas.drawBitmap(small, null, rect, paint);
        } finally {
            if (small != null && small != region && small != photo) small.recycle();
            if (region != null && region != photo) region.recycle();
        }
    }

    private static Bitmap blurSmall(Bitmap bitmap, BooleanSupplier cancelled) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height], result = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            checkCancelled(cancelled);
            for (int x = 0; x < width; x++) {
            int a = 0, r = 0, g = 0, b = 0, count = 0;
            for (int yy = Math.max(0, y - 2); yy <= Math.min(height - 1, y + 2); yy++) {
                for (int xx = Math.max(0, x - 2); xx <= Math.min(width - 1, x + 2); xx++) {
                    int c = pixels[yy * width + xx];
                    int alpha = Color.alpha(c);
                    a += alpha; r += Color.red(c) * alpha; g += Color.green(c) * alpha; b += Color.blue(c) * alpha; count++;
                }
            }
            // Bitmap.getPixels returns straight RGB. Average in premultiplied space,
            // then convert back: transparent neighbors must not darken the color.
            result[y * width + x] = a == 0 ? Color.TRANSPARENT : Color.argb(a / count, r / a, g / a, b / a);
            }
        }
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888);
    }

    public static RectF localBounds(EditorDocument.Item item, int width, int height, Map<String, Bitmap> assets) {
        float size = Math.min(width, height) * item.size;
        if (item.kind == EditorDocument.Kind.IMAGE) {
            Bitmap image = assets.get(item.asset);
            float ratio = image == null ? 1f : (float) image.getWidth() / image.getHeight();
            return new RectF(-size * ratio / 2, -size / 2, size * ratio / 2, size / 2);
        }
        if (item.kind == EditorDocument.Kind.STICKER) return new RectF(-size / 2, -size / 2, size / 2, size / 2);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(size);
        float widest = 0; String[] lines = item.text.split("\n", -1);
        for (String line : lines) widest = Math.max(widest, paint.measureText(line));
        float h = paint.getFontSpacing() * lines.length;
        return new RectF(-widest / 2 - size * .15f, -h / 2, widest / 2 + size * .15f, h / 2);
    }

    public static Matrix itemMatrix(EditorDocument.Item item, int width, int height) {
        Matrix matrix = new Matrix();
        matrix.postScale(item.mirrored ? -item.scale : item.scale, item.scale); matrix.postRotate(item.angle);
        matrix.postTranslate(item.x * width, item.y * height);
        return matrix;
    }

    public static void drawAnnotations(Canvas canvas, EditorDocument.State state, int width, int height,
            Map<String, Bitmap> assets) {
        drawBaseAnnotations(canvas, state, width, height, assets);
        Paint paint = shapePaint();
        for (int i = 0; i < state.items.size(); i++) {
            EditorDocument.Item item = state.items.get(i);
            if (isDrawing(item.kind) && item.drawOnComposition)
                drawTopDrawing(canvas, state, i, width, height, paint);
        }
    }

    private static void drawBaseAnnotations(Canvas canvas, EditorDocument.State state, int width, int height,
            Map<String, Bitmap> assets) {
        Paint paint = shapePaint();
        drawDrawingLayer(canvas, state, width, height, false);
        for (EditorDocument.Item item : state.items) {
            if (item.kind != EditorDocument.Kind.TEXT && item.kind != EditorDocument.Kind.IMAGE && item.kind != EditorDocument.Kind.STICKER) continue;
            int saved = canvas.save(); canvas.concat(itemMatrix(item, width, height));
            RectF bounds = localBounds(item, width, height, assets);
            paint.reset(); paint.setAntiAlias(true); paint.setFilterBitmap(true);
            if (item.kind == EditorDocument.Kind.STICKER) {
                EditorStickers.draw(canvas, item.asset, bounds);
                EditorStickers.drawCaption(canvas, item, bounds);
            } else if (item.kind == EditorDocument.Kind.IMAGE) {
                Bitmap image = assets.get(item.asset);
                if (image != null && !image.isRecycled()) canvas.drawBitmap(image, null, bounds, paint);
            } else {
                float size = Math.min(width, height) * item.size;
                if (item.background) {
                    paint.setColor(0xb0000000); canvas.drawRoundRect(bounds, size * .15f, size * .15f, paint);
                }
                paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER);
                String[] lines = item.text.split("\n", -1);
                float lineHeight = paint.getFontSpacing();
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float y = -(lines.length - 1) * lineHeight / 2 - (metrics.ascent + metrics.descent) / 2;
                for (String line : lines) {
                    if (item.outline) {
                        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size * .09f);
                        paint.setColor(Color.BLACK); canvas.drawText(line, 0, y, paint);
                    }
                    paint.setStyle(Paint.Style.FILL); paint.setColor(item.color); canvas.drawText(line, 0, y, paint);
                    y += lineHeight;
                }
            }
            canvas.restoreToCount(saved);
        }
    }

    /** "Drawing only" erasers affect earlier strokes, without erasing the composed backing. */
    private static void drawTopDrawing(Canvas canvas, EditorDocument.State state, int index, int width, int height,
            Paint paint) {
        EditorDocument.Item item = state.items.get(index);
        if (item.kind == EditorDocument.Kind.ERASER) {
            drawShape(canvas, item, width, height, paint); return;
        }
        boolean isolated = false;
        for (int i = index + 1; i < state.items.size(); i++) {
            EditorDocument.Item next = state.items.get(i);
            if (next.kind == EditorDocument.Kind.ERASER && !next.drawOnComposition) { isolated = true; break; }
        }
        int layer = isolated ? canvas.saveLayer(0, 0, width, height, null) : -1;
        drawShape(canvas, item, width, height, paint);
        if (isolated) {
            for (int i = index + 1; i < state.items.size(); i++) {
                EditorDocument.Item next = state.items.get(i);
                if (next.kind == EditorDocument.Kind.ERASER && !next.drawOnComposition)
                    drawShape(canvas, next, width, height, paint);
            }
            canvas.restoreToCount(layer);
        }
    }

    private static Paint shapePaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    public static boolean isDrawing(EditorDocument.Kind kind) {
        switch (kind) {
            case PEN: case MARKER: case ERASER: case LINE: case ARROW: case RECTANGLE: case OVAL: return true;
            default: return false;
        }
    }

    private static void drawDrawingLayer(Canvas canvas, EditorDocument.State state, int width, int height,
            boolean above) {
        boolean hasDrawings = false;
        for (EditorDocument.Item item : state.items)
            if (isDrawing(item.kind) && item.kind != EditorDocument.Kind.ERASER && item.drawOnComposition == above)
                hasDrawings = true;
        if (!hasDrawings) return;
        int layer = canvas.saveLayer(0, 0, width, height, null);
        Paint paint = shapePaint();
        for (EditorDocument.Item item : state.items) {
            // Both eraser scopes erase earlier strokes in both drawing layers.
            if (isDrawing(item.kind) && (item.kind == EditorDocument.Kind.ERASER || item.drawOnComposition == above))
                drawShape(canvas, item, width, height, paint);
        }
        canvas.restoreToCount(layer);
    }

    private static void drawShape(Canvas canvas, EditorDocument.Item item, int width, int height, Paint paint) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(item.color);
        paint.setAlpha(item.kind == EditorDocument.Kind.MARKER ? 90 : 255);
        float stroke = Math.max(1f, item.size * Math.min(width, height)); paint.setStrokeWidth(stroke);
        paint.setXfermode(item.kind == EditorDocument.Kind.ERASER
                ? new PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
        float x = item.x * width, y = item.y * height, ex = item.endX * width, ey = item.endY * height;
        switch (item.kind) {
            case RECTANGLE: canvas.drawRect(Math.min(x, ex), Math.min(y, ey), Math.max(x, ex), Math.max(y, ey), paint); break;
            case OVAL: canvas.drawOval(Math.min(x, ex), Math.min(y, ey), Math.max(x, ex), Math.max(y, ey), paint); break;
            case LINE: case ARROW:
                canvas.drawLine(x, y, ex, ey, paint);
                if (item.kind == EditorDocument.Kind.ARROW) {
                    double angle = Math.atan2(ey - y, ex - x); float head = Math.max(stroke * 4, Math.min(width, height) * .035f);
                    canvas.drawLine(ex, ey, ex - head * (float) Math.cos(angle - .5),
                            ey - head * (float) Math.sin(angle - .5), paint);
                    canvas.drawLine(ex, ey, ex - head * (float) Math.cos(angle + .5),
                            ey - head * (float) Math.sin(angle + .5), paint);
                }
                break;
            default:
                if (item.points.size() < 2) break;
                Path path = new Path();
                float px = item.points.get(0) * width, py = item.points.get(1) * height;
                path.moveTo(px, py);
                for (int i = 2; i + 1 < item.points.size(); i += 2) {
                    float nx = item.points.get(i) * width, ny = item.points.get(i + 1) * height;
                    path.quadTo(px, py, (px + nx) / 2, (py + ny) / 2); px = nx; py = ny;
                }
                if (item.points.size() == 2) canvas.drawPoint(px, py, paint);
                else { path.lineTo(px, py); canvas.drawPath(path, paint); }
                break;
        }
    }

    public static Bitmap export(Bitmap source, EditorDocument.State state, int maxSide,
            boolean opaque, Map<String, Bitmap> assets) {
        RectF crop = EditorGeometry.cropPixels(state, source.getWidth(), source.getHeight());
        float scale = maxSide == 0 ? 1 : Math.min(1f, maxSide / Math.max(crop.width(), crop.height()));
        int width = Math.max(1, Math.round(crop.width() * scale)), height = Math.max(1, Math.round(crop.height() * scale));
        boolean scene = hasSceneMasks(state);
        Bitmap processed = null, output = null;
        try {
            processed = scene ? prepareScene(source, state, assets, () -> false) : preparePhoto(source, state);
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            if (opaque) canvas.drawColor(Color.WHITE);
            canvas.scale(scale, scale); canvas.translate(-crop.left, -crop.top);
            canvas.concat(EditorGeometry.sourceToFrame(state, source.getWidth(), source.getHeight()));
            canvas.drawBitmap(processed, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            if (!scene) drawAnnotations(canvas, state, source.getWidth(), source.getHeight(), assets);
            return output;
        } catch (RuntimeException | OutOfMemoryError e) {
            if (output != null) output.recycle(); throw e;
        } finally {
            if (processed != null) processed.recycle();
        }
    }
}
