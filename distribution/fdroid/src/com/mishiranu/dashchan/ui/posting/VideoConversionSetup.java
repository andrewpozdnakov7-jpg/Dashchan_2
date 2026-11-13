package com.mishiranu.dashchan.ui.posting;
import android.content.Context;
import android.app.Dialog;
import java.util.function.Supplier;
/** Inert UI contract; the ordinary attachment options dialog is used in F-Droid. */
public final class VideoConversionSetup {
    private VideoConversionSetup() {}
    public static Context materialContext(Context context) { return context; }
    public static Panel create(Context c, AttachmentHolder h, Supplier<VideoSizeSummary> s, Runnable r) {
        throw new UnsupportedOperationException("Video export unavailable");
    }
    public static final class Panel extends Dialog {
        private Panel(Context context) { super(context); }
        public void refresh() {}
    }
}
