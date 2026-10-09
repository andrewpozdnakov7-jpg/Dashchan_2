package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Entry-request ownership with real Bitmap objects; player fields remain empty to avoid native startup. */
@RunWith(AndroidJUnit4.class)
public class PipTransferRegistryTest {
	private static File fixture(String name) {
		return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name);
	}

	private static void recycle(Bitmap bitmap) {
		if (!bitmap.isRecycled()) bitmap.recycle();
	}

	@Test public void wrongPathCannotConsumeARequestAndMatchingPathConsumesItOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoUnit.PictureInPictureSource owner = new VideoUnit.PictureInPictureSource();
			File file = fixture("pip-transfer.mp4");
			Bitmap preview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			try {
				PipTransferRegistry.registerTransfer(owner, null, file, preview, null, null);
				assertNull(PipTransferRegistry.takePendingTransfer(fixture("different.mp4").getAbsolutePath()));
				PipTransferRegistry.PendingTransfer transfer = PipTransferRegistry.takePendingTransfer(file.getAbsolutePath());
				assertNotNull(transfer);
				assertSame(owner, transfer.source);
				assertSame(preview, transfer.previewFrame);
				assertFalse(preview.isRecycled());
				assertNull(PipTransferRegistry.takePendingTransfer(file.getAbsolutePath()));
			} finally {
				PipTransferRegistry.cancelPendingTransfer(owner, null);
				recycle(preview);
			}
		});
	}

	@Test public void wrongOwnerCannotCancelOrRecycleAnotherOwnersPreview() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoUnit.PictureInPictureSource owner = new VideoUnit.PictureInPictureSource();
			VideoUnit.PictureInPictureSource other = new VideoUnit.PictureInPictureSource();
			File file = fixture("pip-owner.mp4");
			Bitmap preview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			try {
				PipTransferRegistry.registerTransfer(owner, null, file, preview, null, null);
				PipTransferRegistry.cancelPendingTransfer(other, null);
				assertFalse(preview.isRecycled());
				assertNotNull(PipTransferRegistry.takePendingTransfer(file.getAbsolutePath()));
				assertFalse(preview.isRecycled());
			} finally {
				PipTransferRegistry.cancelPendingTransfer(owner, null);
				recycle(preview);
			}
		});
	}

	@Test public void matchingCancellationRecyclesOnlyThePendingPreview() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoUnit.PictureInPictureSource owner = new VideoUnit.PictureInPictureSource();
			File file = fixture("pip-cancel.mp4");
			Bitmap preview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			try {
				PipTransferRegistry.registerTransfer(owner, null, file, preview, null, null);
				PipTransferRegistry.cancelPendingTransfer(owner, null);
				assertTrue(preview.isRecycled());
				assertNull(PipTransferRegistry.takePendingTransfer(file.getAbsolutePath()));
				PipTransferRegistry.cancelPendingTransfer(owner, null);
			} finally {
				PipTransferRegistry.cancelPendingTransfer(owner, null);
				recycle(preview);
			}
		});
	}

	@Test public void replacementRetiresOnlyTheOldPreviewAndKeepsTheNewRequest() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			VideoUnit.PictureInPictureSource first = new VideoUnit.PictureInPictureSource();
			VideoUnit.PictureInPictureSource second = new VideoUnit.PictureInPictureSource();
			File firstFile = fixture("pip-first.mp4");
			File secondFile = fixture("pip-second.mp4");
			Bitmap firstPreview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			Bitmap secondPreview = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			try {
				PipTransferRegistry.registerTransfer(first, null, firstFile, firstPreview, null, null);
				PipTransferRegistry.registerTransfer(second, null, secondFile, secondPreview, null, null);
				assertTrue(firstPreview.isRecycled());
				assertFalse(secondPreview.isRecycled());
				PipTransferRegistry.cancelPendingTransfer(first, null);
				assertFalse(secondPreview.isRecycled());
				assertNull(PipTransferRegistry.takePendingTransfer(firstFile.getAbsolutePath()));
				PipTransferRegistry.PendingTransfer transfer =
						PipTransferRegistry.takePendingTransfer(secondFile.getAbsolutePath());
				assertNotNull(transfer);
				assertSame(second, transfer.source);
				assertSame(secondPreview, transfer.previewFrame);
			} finally {
				PipTransferRegistry.cancelPendingTransfer(first, null);
				PipTransferRegistry.cancelPendingTransfer(second, null);
				recycle(firstPreview);
				recycle(secondPreview);
			}
		});
	}
}
