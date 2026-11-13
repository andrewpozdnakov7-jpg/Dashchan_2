package com.mishiranu.dashchan.util;

/** Absolute output bounds shared by editor export and pending upload conversion; never upscale. */
public final class ImageOutputSizing {
    private ImageOutputSizing() {}
    public static int[] fit(float width, float height, int targetWidth, int targetHeight) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width<=0 || height<=0
                || targetWidth<0 || targetHeight<0 || (targetWidth==0)!=(targetHeight==0))
            throw new IllegalArgumentException("Invalid image dimensions");
        if(targetWidth==0) return new int[]{Math.max(1,Math.round(width)),Math.max(1,Math.round(height))};
        // An integral legacy downscale floors each axis. Keep its one-pixel rounding exactly.
        if(targetWidth<=width && targetHeight<=height
                && Math.abs(targetWidth*(double)height-targetHeight*(double)width)<=Math.max(width,height))
            return new int[]{targetWidth,targetHeight};
        double scale=Math.min(1d,Math.min(targetWidth/(double)width,targetHeight/(double)height));
        return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
    }
}
