package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real EGL fixture, prepared but not executed. No playback, network or native decoder required. */
@RunWith(AndroidJUnit4.class)
public class VideoFramePreparationTest {
	@Test public void latchedFrameKeepsColorChannelsAndTopBottomOrientation() throws Exception {
		try (VideoFramePreparation output = new VideoFramePreparation(64, 32)) {
			Surface surface = output.createSurface();
			Bitmap frame = null;
			try {
				Canvas canvas = surface.lockCanvas(null);
				try {
					Paint paint = new Paint();
					paint.setColor(Color.RED); canvas.drawRect(0, 0, 32, 16, paint);
					paint.setColor(Color.GREEN); canvas.drawRect(32, 0, 64, 16, paint);
					paint.setColor(Color.BLUE); canvas.drawRect(0, 16, 32, 32, paint);
					paint.setColor(Color.WHITE); canvas.drawRect(32, 16, 64, 32, paint);
				} finally { surface.unlockCanvasAndPost(canvas); }
				frame = output.awaitFrame(() -> false);
				assertNotNull(frame); assertEquals(64, frame.getWidth()); assertEquals(32, frame.getHeight());
				assertEquals(Color.RED, frame.getPixel(16, 8)); assertEquals(Color.GREEN, frame.getPixel(48, 8));
				assertEquals(Color.BLUE, frame.getPixel(16, 24)); assertEquals(Color.WHITE, frame.getPixel(48, 24));
			} finally { surface.release(); if (frame != null) frame.recycle(); }
		}
	}

	@Test public void cancellationDoesNotWaitForOrExportAnEmptyFrame() throws Exception {
		try (VideoFramePreparation output = new VideoFramePreparation(64, 32)) {
			assertNull(output.awaitFrame(() -> true));
		}
	}
}
