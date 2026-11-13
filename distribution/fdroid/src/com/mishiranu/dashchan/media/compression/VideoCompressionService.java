package com.mishiranu.dashchan.media.compression;
import android.content.Context;
/** Inert posting contract, not an Android service or encoder entry point. */
public final class VideoCompressionService {
    private VideoCompressionService() {}
    public static boolean isBusy() { return false; }
    public static boolean start(Context context, VideoCompressionJob job) { return false; }
    public static void cancel(VideoCompressionJob job) {}
}
