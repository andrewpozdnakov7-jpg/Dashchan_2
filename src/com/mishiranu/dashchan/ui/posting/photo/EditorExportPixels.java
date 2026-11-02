package com.mishiranu.dashchan.ui.posting.photo;

import android.graphics.Bitmap;

/** Small reusable Java buffers; the export bitmap itself keeps the requested resolution. */
final class EditorExportPixels {
    private static final int BAND_PIXELS = 256 * 1024;
    private EditorExportPixels() {}

    static int bandRows(int width) {
        return Math.max(1, Math.min(128, BAND_PIXELS / width));
    }

    interface Adjustment { void apply(int[] pixels, int count); }

    static void adjust(Bitmap bitmap, Adjustment adjustment) {
        int width = bitmap.getWidth(), height = bitmap.getHeight(), rows = bandRows(width);
        int[] pixels = new int[width * rows];
        for (int y = 0; y < height; y += rows) {
            int count = Math.min(rows, height - y);
            bitmap.getPixels(pixels, 0, width, 0, y, width, count);
            adjustment.apply(pixels, width * count);
            bitmap.setPixels(pixels, 0, width, 0, y, width, count);
        }
    }

    static void sharpen(Bitmap bitmap, float amount) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 3 || height < 3) return;
        int[] previous = new int[width], current = new int[width], next = new int[width], output = new int[width];
        bitmap.getPixels(previous, 0, width, 0, 0, width, 1);
        bitmap.getPixels(current, 0, width, 0, 1, width, 1);
        for (int y = 1; y < height - 1; y++) {
            bitmap.getPixels(next, 0, width, 0, y + 1, width, 1);
            System.arraycopy(current, 0, output, 0, width);
            for (int x = 1; x < width - 1; x++) {
                int c = current[x];
                int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                float ar = (((previous[x] >>> 16) & 255) + ((next[x] >>> 16) & 255)
                        + ((current[x - 1] >>> 16) & 255) + ((current[x + 1] >>> 16) & 255)) / 4f;
                float ag = (((previous[x] >>> 8) & 255) + ((next[x] >>> 8) & 255)
                        + ((current[x - 1] >>> 8) & 255) + ((current[x + 1] >>> 8) & 255)) / 4f;
                float ab = ((previous[x] & 255) + (next[x] & 255) + (current[x - 1] & 255) + (current[x + 1] & 255)) / 4f;
                output[x] = (c & 0xff000000) | channel(r + (r - ar) * amount) << 16
                        | channel(g + (g - ag) * amount) << 8 | channel(b + (b - ab) * amount);
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1);
            int[] swap = previous; previous = current; current = next; next = swap;
        }
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
}
