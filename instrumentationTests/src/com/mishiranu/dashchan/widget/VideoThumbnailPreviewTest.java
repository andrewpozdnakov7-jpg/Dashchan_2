package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import com.mishiranu.dashchan.content.Preferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Cache/view ownership and preference gates only. No decoder/network; prepared, not run. */
public class VideoThumbnailPreviewTest {
	private static final String RESOURCE = "fixture:video-thumbnail";
	private static Bitmap frame(int color) {
		Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); b.eraseColor(color); return b;
	}
	private static void withPreference(boolean enabled, java.util.function.Consumer<Context> action) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS);
			try {
				Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, enabled).close();
				action.accept(InstrumentationRegistry.getInstrumentation().getTargetContext());
			} finally {
				VideoThumbnailPreview.invalidate(RESOURCE);
				// Release all fixture cache entries and view overrides before restoring the original setting.
				Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false).close();
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS).close();
			}
		});
	}
	private static AttachmentView thumbnail(Context context) {
		AttachmentView view = new AttachmentView(context, null); view.resetImage("site", AttachmentView.Overlay.NONE);
		view.setCropEnabled(true); view.layout(0, 0, 40, 40); return view;
	}
	private static int color(AttachmentView view) {
		Bitmap pixels = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
		try { view.draw(new Canvas(pixels)); return pixels.getPixel(20, 20); } finally { pixels.recycle(); }
	}
	@Test public void disabledSettingNeverCallsTheCaptureSupplier() {
		withPreference(false, context -> {
			int[] captures = {0};
			assertFalse(VideoThumbnailPreview.publish(context, RESOURCE, () -> { captures[0]++; return frame(Color.RED); }));
			assertEquals(0, captures[0]); assertFalse(Preferences.DEFAULT_UPDATE_VIDEO_THUMBNAILS);
		});
	}
	@Test public void lateSiteThumbnailCannotOverwriteVideoFrameAndEvictionRestoresOriginal() {
		withPreference(true, context -> {
			AttachmentView view = thumbnail(context); Bitmap original = frame(Color.BLUE), video = frame(Color.RED);
			try {
				long binding = view.getImageBindingGeneration(); VideoThumbnailPreview.bind(view, RESOURCE);
				assertTrue(VideoThumbnailPreview.publish(context, RESOURCE, () -> video));
				view.handleLoadedImage("site", original, false, true); assertEquals(Color.RED, color(view));
				assertEquals(binding, view.getImageBindingGeneration());
				VideoThumbnailPreview.invalidate(RESOURCE); assertTrue(video.isRecycled());
				assertEquals(Color.BLUE, color(view)); assertEquals(binding, view.getImageBindingGeneration());
			} finally { VideoThumbnailPreview.invalidate(RESOURCE); VideoThumbnailPreview.unbind(view); if (!video.isRecycled()) video.recycle(); original.recycle(); }
		});
	}
	@Test public void recycledBindingRejectsUpdatesForThePreviousMedia() {
		withPreference(true, context -> {
			AttachmentView view = thumbnail(context); Bitmap original = frame(Color.GREEN);
			try {
				VideoThumbnailPreview.bind(view, RESOURCE); view.resetImage("another_site_thumbnail");
				view.handleLoadedImage("another_site_thumbnail", original, false, true);
				assertTrue(VideoThumbnailPreview.publish(context, RESOURCE, () -> frame(Color.RED)));
				assertEquals(Color.GREEN, color(view));
			} finally { VideoThumbnailPreview.unbind(view); original.recycle(); }
		});
	}
	@Test public void disablingTheExperimentClearsLiveOverrideBeforeRecyclingItsPixels() {
		withPreference(true, context -> {
			AttachmentView view = thumbnail(context); Bitmap original = frame(Color.BLUE), video = frame(Color.RED);
			try {
				view.handleLoadedImage("site", original, false, true); VideoThumbnailPreview.bind(view, RESOURCE);
				assertTrue(VideoThumbnailPreview.publish(context, RESOURCE, () -> video)); assertEquals(Color.RED, color(view));
				Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false).close();
				assertTrue(video.isRecycled()); assertEquals(Color.BLUE, color(view));
			} finally { VideoThumbnailPreview.invalidate(RESOURCE); VideoThumbnailPreview.unbind(view); if (!video.isRecycled()) video.recycle(); original.recycle(); }
		});
	}
}
