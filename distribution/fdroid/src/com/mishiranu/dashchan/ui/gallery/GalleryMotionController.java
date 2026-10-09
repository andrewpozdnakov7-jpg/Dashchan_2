package com.mishiranu.dashchan.ui.gallery;

import android.view.View;
import com.mishiranu.dashchan.widget.PhotoView;

/** F-Droid keeps its existing gallery implementation. */
public final class GalleryMotionController {
	public GalleryMotionController(View root) {}
	public void setSource(View view) {}
	public void setMediaChrome(View[] views) {}
	public void enter(PhotoView photo, Object item, View chrome, boolean restoring) {}
	public void enter(PhotoView photo, Object item, View chrome, boolean restoring, View surface) {}
	public boolean close(PhotoView photo, Object item, View chrome, Runnable dismiss) { return false; }
	public boolean close(PhotoView photo, Object item, View chrome, Runnable dismiss, View surface) { return false; }
	public void finish() {}
	public boolean isClosing() { return false; }
	public static void finishHost(View host) {}
	public static boolean blocks(View host) { return false; }
	public static boolean mode(View view, boolean enter, int duration, boolean scaleContent) { return false; }
	public static Preview capturePreview(PhotoView photo) { return null; }
	public static void revealPhoto(PhotoView photo, Preview preview, boolean hadImage) {}
	public static final class Preview { public void discard() {} }
}
