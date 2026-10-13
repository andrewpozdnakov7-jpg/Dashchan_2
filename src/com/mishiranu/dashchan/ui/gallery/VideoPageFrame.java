package com.mishiranu.dashchan.ui.gallery;

import android.graphics.Bitmap;

/** Independent, bounded bitmap shared by adjacent pages and the startup bridge; never manually recycled. */
final class VideoPageFrame {
	final Bitmap bitmap;
	final int width, height;
	VideoPageFrame(Bitmap bitmap, int width, int height) {
		this.bitmap = bitmap; this.width = width; this.height = height;
	}
}
