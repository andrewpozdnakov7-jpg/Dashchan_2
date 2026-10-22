package com.mishiranu.dashchan.ui.posting.photo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local opaque RGB/HSV draft. Mode switches never quantize an arbitrary color. */
public final class EditorColorState {
    public static final int COLUMNS = 12, ROWS = 10;
    private static final double[] HUES = {190, 220, 250, 280, 320, 0, 25, 40, 55, 70, 95, 120};
    private static final double[] SATURATIONS = {1, 1, 1, 1, 1, .75, .50, .25, .10};
    private static final double[] VALUES = {.25, .40, .55, .75, 1, 1, 1, 1, 1};
    private double hue, saturation, value;
    private int color;
    public EditorColorState(int color) { setRgb(color); }
    public int color() { return color; }
    public double hue() { return hue; }
    public double saturation() { return saturation; }
    public double value() { return value; }
    public void setRgb(int rgb) {
        color = rgb | 0xff000000;
        double r = ((rgb >> 16) & 255) / 255d, g = ((rgb >> 8) & 255) / 255d, b = (rgb & 255) / 255d;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        value = max; saturation = max == 0 ? 0 : delta / max;
        if (delta > 0) {
            double h = max == r ? (g - b) / delta : max == g ? 2 + (b - r) / delta : 4 + (r - g) / delta;
            hue = normalize(h * 60);
        }
        // Gray and black have no hue; keep the user's last meaningful hue.
    }
    public void setHueSaturation(double h, double s) {
        hue = normalize(h); saturation = clamp(s); update();
    }
    public void setValue(double v) { value = clamp(v); update(); }
    private void update() { color = hsv(hue, saturation, value); }
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    private static double normalize(double h) { return (h % 360 + 360) % 360; }
    public static int hsv(double h, double s, double v) {
        h = normalize(h) / 60; s = clamp(s); v = clamp(v);
        double c = v * s, x = c * (1 - Math.abs(h % 2 - 1)), m = v - c;
        double r, g, b;
        if (h < 1) { r = c; g = x; b = 0; } else if (h < 2) { r = x; g = c; b = 0; }
        else if (h < 3) { r = 0; g = c; b = x; } else if (h < 4) { r = 0; g = x; b = c; }
        else if (h < 5) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xff000000 | ((int) Math.round((r + m) * 255) << 16)
                | ((int) Math.round((g + m) * 255) << 8) | (int) Math.round((b + m) * 255);
    }
    public static int cell(int index) {
        if (index < 0 || index >= COLUMNS * ROWS) throw new IllegalArgumentException("Color cell");
        int row = index / COLUMNS, column = index % COLUMNS;
        return row == 0 ? hsv(0, 0, 1 - column / (double) (COLUMNS - 1))
                : hsv(HUES[column], SATURATIONS[row - 1], VALUES[row - 1]);
    }
    public int selectedCell() {
        for (int i = 0; i < COLUMNS * ROWS; i++) if (cell(i) == color) return i;
        return -1;
    }
    public static String hex(int color) { return String.format(Locale.US, "#%06X", color & 0xffffff); }
    public static Integer parseHex(String text) {
        String value = text.trim();
        return value.matches("#[0-9a-fA-F]{6}") ? 0xff000000 | Integer.parseInt(value.substring(1), 16) : null;
    }
    public static List<Integer> recent(List<Integer> previous, int confirmed) {
        ArrayList<Integer> result = new ArrayList<>(); result.add(confirmed | 0xff000000);
        for (Integer color : previous) if (color != null && !result.contains(color | 0xff000000) && result.size() < 8)
            result.add(color | 0xff000000);
        return result;
    }
}
