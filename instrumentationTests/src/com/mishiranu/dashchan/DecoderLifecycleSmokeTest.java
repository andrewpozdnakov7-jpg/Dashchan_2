package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.media.GifDecoder;
import com.mishiranu.dashchan.media.VideoPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real GIF JNI; no websites, audio playback or gallery/PiP ownership changes. */
@RunWith(AndroidJUnit4.class)
public class DecoderLifecycleSmokeTest {
	@Test public void gifCloseIsIdempotentAndAnOldDrawableIsHarmless() throws Exception {
		File file = fixture(Base64.decode(
				"R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==", Base64.DEFAULT));
		try {
			// Construction is allowed on a worker; draw/animation/close normally run on UI.
			try (GifDecoder decoder = new GifDecoder(file)) {
				InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
					Drawable drawable = decoder.getDrawable();
					assertEquals(1, drawable.getIntrinsicWidth());
					assertEquals(1, drawable.getIntrinsicHeight());
					Bitmap target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
					try {
						drawable.setBounds(0, 0, 2, 2);
						drawable.draw(new Canvas(target));
						decoder.close();
						decoder.recycle();
						decoder.run();
						// No access to the freed native pointer/bitmap after close.
						drawable.draw(new Canvas(target));
					} finally {
						target.recycle();
					}
				});
			}
		} finally {
			assertTrue(file.delete());
		}
	}

	@Test public void failedGifConstructionDoesNotReturnAPartialDecoder() throws Exception {
		File file = fixture("not a GIF".getBytes(StandardCharsets.US_ASCII));
		try {
			for (int i = 0; i < 8; i++) {
				try (GifDecoder ignored = new GifDecoder(file)) {
					fail("Invalid GIF must fail initialization");
				} catch (IOException expected) {
					assertNotNull(expected.getMessage());
				}
			}
		} finally {
			assertTrue(file.delete());
		}
	}

	@Test public void videoCloseBeforeInitIsSafeAndIdempotent() {
		try (VideoPlayer player = new VideoPlayer(null, true)) {
			player.close();
			player.destroy();
			assertFalse(player.isPlaying());
		}
	}

	private static File fixture(byte[] bytes) throws IOException {
		File file = File.createTempFile("decoder-smoke-", ".gif",
				InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(bytes);
		}
		return file;
	}
}
