package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.graphics.SimpleBitmapDrawable;
import com.mishiranu.dashchan.widget.PhotoView;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared Canvas fixtures, without a decoder, device video, network or saved media. */
@RunWith(AndroidJUnit4.class)
public class VideoStartupPreviewGeometryTest {
	private static FrameLayout createViewport(Context context) {
		FrameLayout parent = new FrameLayout(context);
		parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
		parent.layout(0, 0, 400, 600);
		return parent;
	}

	private static Bitmap greenBitmap() {
		Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.GREEN);
		return bitmap;
	}

	@Test public void clearedPhotoStillCoversAnUnmeasuredVideo() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			FrameLayout root = createViewport(context);
			PhotoView photo = new PhotoView(context, null);
			FrameLayout surfaceParent = new FrameLayout(context);
			root.addView(photo, new FrameLayout.LayoutParams(400, 600));
			root.addView(surfaceParent, new FrameLayout.LayoutParams(400, 600));
			photo.layout(0, 0, 400, 600);
			surfaceParent.layout(0, 0, 400, 600);
			Bitmap cached = greenBitmap();
			Bitmap frame = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
			try {
				SimpleBitmapDrawable thumbnail = new SimpleBitmapDrawable(cached, 400, 200, false);
				photo.setImage(thumbnail, false, true, false);
				VideoStartupPreview.Geometry captured = VideoStartupPreview.captureGeometry(photo, surfaceParent);
				assertNotNull(captured);
				photo.recycle();
				thumbnail.recycle();
				assertFalse(cached.isRecycled());
				View video = new View(context);
				surfaceParent.addView(video);
				assertEquals(0, video.getWidth());
				assertEquals(0, video.getHeight());
				VideoStartupPreview.Geometry selected =
						VideoStartupPreview.resolveGeometry(surfaceParent, video, captured, thumbnail);
				assertSame(captured, selected);
				assertTrue(selected.draw(new Canvas(frame), thumbnail, null));
				assertEquals(Color.GREEN, frame.getPixel(200, 300));
				assertEquals(Color.TRANSPARENT, frame.getPixel(200, 100));
				assertEquals(Color.TRANSPARENT, frame.getPixel(200, 500));

				// Once laid out, the live video bounds supersede the captured photo rectangle.
				video.layout(20, 40, 220, 140);
				selected = VideoStartupPreview.resolveGeometry(surfaceParent, video, captured, thumbnail);
				assertNotSame(captured, selected);
				frame.eraseColor(Color.TRANSPARENT);
				assertTrue(selected.draw(new Canvas(frame), thumbnail, null));
				assertEquals(Color.GREEN, frame.getPixel(100, 80));
				assertEquals(Color.TRANSPARENT, frame.getPixel(200, 300));
			} finally {
				photo.recycle();
				frame.recycle();
				cached.recycle();
			}
		});
	}

	@Test public void snapshotCopiesTransformsAndClipsTheImageToItsViewport() {
		RectF image = new RectF(-50, 80, 250, 180);
		RectF viewport = new RectF(0, 0, 200, 300);
		Matrix transform = new Matrix();
		transform.setTranslate(30, 10);
		VideoStartupPreview.Geometry snapshot = new VideoStartupPreview.Geometry(image, viewport, transform, 1f);
		image.setEmpty();
		viewport.setEmpty();
		transform.reset();
		Bitmap cached = greenBitmap();
		Bitmap frame = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
		try {
			assertTrue(snapshot.draw(new Canvas(frame), new SimpleBitmapDrawable(cached, 300, 100, false), null));
			assertEquals(Color.GREEN, frame.getPixel(100, 120));
			assertEquals(Color.TRANSPARENT, frame.getPixel(20, 120));
			assertEquals(Color.TRANSPARENT, frame.getPixel(240, 120));
			assertEquals(Color.TRANSPARENT, frame.getPixel(100, 200));
		} finally {
			frame.recycle();
			cached.recycle();
		}
	}

	@Test public void firstOpenCanFitACachedThumbnailWithoutEitherMeasuredImageView() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			FrameLayout parent = createViewport(context);
			View video = new View(context);
			parent.addView(video);
			Bitmap cached = greenBitmap();
			Bitmap frame = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
			try {
				SimpleBitmapDrawable thumbnail = new SimpleBitmapDrawable(cached, 200, 400, false);
				VideoStartupPreview.Geometry geometry =
						VideoStartupPreview.resolveGeometry(parent, video, null, thumbnail);
				assertNotNull(geometry);
				assertTrue(geometry.draw(new Canvas(frame), thumbnail, null));
				assertEquals(Color.GREEN, frame.getPixel(200, 300));
				assertEquals(Color.TRANSPARENT, frame.getPixel(20, 300));
				assertEquals(Color.TRANSPARENT, frame.getPixel(380, 300));
			} finally {
				frame.recycle();
				cached.recycle();
			}
		});
	}
}
