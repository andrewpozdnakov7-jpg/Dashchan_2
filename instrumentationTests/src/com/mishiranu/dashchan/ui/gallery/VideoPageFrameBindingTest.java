package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.graphics.SimpleBitmapDrawable;
import com.mishiranu.dashchan.widget.PhotoView;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared page/bitmap ownership fixtures; no native decoder, files or network required. */
@RunWith(AndroidJUnit4.class)
public class VideoPageFrameBindingTest {
	@Test public void readyFrameReplacesThumbnailAndRejectsBothImmediateAndLateThumbnailCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			PagerInstance.ViewHolder holder = new PagerInstance.ViewHolder();
			holder.photoView = new PhotoView(context, null);
			Bitmap thumbnail = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
			thumbnail.eraseColor(Color.RED);
			Bitmap decoded = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
			decoded.eraseColor(Color.GREEN);
			Bitmap output = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
			try {
				holder.simpleBitmapDrawable = new SimpleBitmapDrawable(thumbnail, false);
				holder.photoView.setImage(holder.simpleBitmapDrawable, false, true, false);
				holder.photoViewThumbnail = true;
				VideoPageFrame frame = new VideoPageFrame(decoded, 1280, 720);
				assertTrue(PagerUnit.bindVideoPageFrame(holder, frame));
				assertSame(frame, holder.videoPageFrame); assertTrue(holder.photoViewThumbnail);
				assertFalse(PagerUnit.canApplyThumbnail(holder, true, true));
				assertFalse(PagerUnit.canApplyThumbnail(holder, false, true));
				holder.simpleBitmapDrawable.setBounds(0, 0, 4, 4);
				holder.simpleBitmapDrawable.draw(new Canvas(output));
				assertEquals(Color.GREEN, output.getPixel(2, 2));
				SimpleBitmapDrawable borrowed = holder.simpleBitmapDrawable;
				assertTrue(PagerUnit.bindVideoPageFrame(holder, frame));
				assertSame(borrowed, holder.simpleBitmapDrawable); // Rebinding does not restart image geometry.
				holder.recyclePhotoView();
				assertNull(holder.videoPageFrame); assertFalse(decoded.isRecycled());
				borrowed.draw(new Canvas(output)); // Startup bridge may still borrow it after page cleanup.
				assertEquals(Color.GREEN, output.getPixel(2, 2));
				assertTrue(PagerUnit.canApplyThumbnail(holder, false, true));
				assertFalse(PagerUnit.canApplyThumbnail(holder, false, false));
			} finally {
				holder.recyclePhotoView(); thumbnail.recycle(); decoded.recycle(); output.recycle();
			}
		});
	}

	@Test public void missingFrameKeepsOrdinaryThumbnailAndDoesNotTouchImagePages() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			PagerInstance.ViewHolder holder = new PagerInstance.ViewHolder();
			holder.photoView = new PhotoView(context, null);
			Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
			try {
				holder.simpleBitmapDrawable = new SimpleBitmapDrawable(bitmap, false);
				holder.photoView.setImage(holder.simpleBitmapDrawable, false, true, false);
				SimpleBitmapDrawable original = holder.simpleBitmapDrawable;
				assertFalse(PagerUnit.bindVideoPageFrame(holder, null));
				assertSame(original, holder.simpleBitmapDrawable);
				assertNull(holder.videoPageFrame);
				assertTrue(PagerUnit.canApplyThumbnail(holder, true, true));
			} finally { holder.recyclePhotoView(); bitmap.recycle(); }
		});
	}
}
