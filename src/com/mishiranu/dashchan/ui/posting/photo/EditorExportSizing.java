package com.mishiranu.dashchan.ui.posting.photo;

/** Export dimensions depend on the original crop and explicit user choice, never preview or heap size. */
public final class EditorExportSizing {
    private EditorExportSizing() {}

    private static void validate(float width, float height, int side) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0 || side < 0)
            throw new IllegalArgumentException("Invalid export dimensions");
    }

    public static int decodeSample(float cropWidth, float cropHeight, int side) {
        validate(cropWidth, cropHeight, side);
        if (side == 0) return 1;
        double wanted = Math.max(1d, Math.max(cropWidth, cropHeight) / (double) side);
        int sample = 1;
        // Keep enough decoded pixels for the requested result, including a small crop of a huge source.
        while (sample < (1 << 29) && sample * 2d <= wanted) sample *= 2;
        return sample;
    }

    public static int[] dimensions(float cropWidth, float cropHeight, int side) {
        validate(cropWidth, cropHeight, side);
        double scale = side == 0 ? 1d : Math.min(1d, side / (double) Math.max(cropWidth, cropHeight));
        return new int[] {Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.round(cropWidth * scale))),
                Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.round(cropHeight * scale)))};
    }
}
