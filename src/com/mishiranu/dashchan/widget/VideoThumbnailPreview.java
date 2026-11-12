package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.UiThread;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.VideoDiagnostics;
import com.mishiranu.dashchan.util.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Process-local, bounded video thumbnails. UI thread only; never writes ImageLoader's disk/cache entries. */
@UiThread
public final class VideoThumbnailPreview implements BackPreviewBudget.Cache {
	public static final int MAX_EDGE = 640;
	private static final int MAX_ENTRIES = 20;
	private static VideoThumbnailPreview instance;
	private final LinkedHashMap<String, Bitmap> frames = new LinkedHashMap<>(16, .75f, true);
	// Views borrow current pixels and may lease a previous frame for a bounded post-thumbnail fade.
	// Clear their override (including transition leases) before releasing/recycling an entry.
	private final WeakHashMap<AttachmentView, String> bindings = new WeakHashMap<>();
	private final PreviewMemoryPolicy memory;
	private final SharedPreferences.Listener preferences = key -> {
		if (Preferences.KEY_UPDATE_VIDEO_THUMBNAILS.equals(key) && !Preferences.isUpdateVideoThumbnails()) clear();
	};
	private VideoThumbnailPreview(Context context) {
		memory = new PreviewMemoryPolicy(context.getApplicationContext(), reason -> clear(), this);
		Preferences.PREFERENCES.register(preferences);
	}
	private static VideoThumbnailPreview obtain(Context context) {
		if (instance == null) instance = new VideoThumbnailPreview(context);
		return instance;
	}
	public static void bind(AttachmentView view, String resource) {
		if (resource == null) { unbind(view); return; }
		VideoThumbnailPreview cache = obtain(view.getContext());
		cache.bindings.put(view, resource);
		// Remember weak media bindings while disabled so the next publish after re-enabling can update them.
		view.setVideoThumbnailPreview(Preferences.isUpdateVideoThumbnails() ? cache.frames.get(resource) : null);
	}
	public static void unbind(AttachmentView view) {
		if (instance != null) instance.bindings.remove(view);
		view.setVideoThumbnailPreview(null);
	}
	public static void invalidate(String resource) {
		if (instance != null && resource != null) instance.remove(resource);
	}
	public static boolean publish(Context context, String resource, Supplier<Bitmap> capture) {
		if (!Preferences.isUpdateVideoThumbnails() || resource == null) return false;
		VideoThumbnailPreview cache = obtain(context);
		// Include the temporary orientation copy in admission; the final frame uses at most half of this.
		Bitmap next = BackPreviewBudget.captureBitmap(context, 8L * MAX_EDGE * MAX_EDGE, capture);
		if (next == null) return false;
		Bitmap previous = cache.frames.put(resource, next);
		cache.bindings.forEach((view, key) -> { if (resource.equals(key)) view.setVideoThumbnailPreview(next, true); });
		BackPreviewBudget.release(previous);
		while (cache.frames.size() > MAX_ENTRIES) cache.evictOldest();
		if (VideoDiagnostics.isExtendedRecording()) VideoDiagnostics.recordUi("gallery video_thumbnail published width="
				+ next.getWidth() + " height=" + next.getHeight() + " entries=" + cache.frames.size());
		return true;
	}
	private boolean remove(String resource) {
		Bitmap frame = frames.remove(resource);
		if (frame == null) return false;
		bindings.forEach((view, key) -> { if (resource.equals(key)) view.setVideoThumbnailPreview(null); });
		BackPreviewBudget.release(frame);
		return true;
	}
	@Override public boolean evictOldest() {
		return !frames.isEmpty() && remove(frames.keySet().iterator().next());
	}
	private void clear() {
		bindings.keySet().forEach(view -> view.setVideoThumbnailPreview(null));
		for (Bitmap frame : frames.values()) BackPreviewBudget.release(frame);
		frames.clear();
	}
}
