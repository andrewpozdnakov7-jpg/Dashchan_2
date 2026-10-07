package com.mishiranu.dashchan.ui.posting.photo;

/** Normalized to the source short side, shared by future regions and existing region edits. */
public final class EditorBlurPresets {
    private EditorBlurPresets() {}
    private static final float[] SIZES = {.006f, .018f, .045f};
    public static int clamp(int index) { return Math.max(0, Math.min(2, index)); }
    public static int nearest(float size) {
        int best = 0;
        for (int i = 1; i < SIZES.length; i++)
            if (Math.abs(size - SIZES[i]) < Math.abs(size - SIZES[best])) best = i;
        return best;
    }
    public static float size(int index) { return SIZES[clamp(index)]; }
    public static float brush(float size) {
        return Float.isFinite(size) ? Math.max(.001f, Math.min(.1f, size)) : .025f;
    }
}
