package com.mishiranu.dashchan.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.util.IdentityHashMap;

/** UI-thread-only, process-local ledger shared by page and gallery predictive snapshots. */
public final class BackPreviewBudget {
	public static final long NORMAL_BYTES = 48L * 1024 * 1024, LOW_MEMORY_BYTES = 12L * 1024 * 1024;
	public static final float MIN_LINEAR_SCALE = .35f;
	private static final IdentityHashMap<Bitmap, Long> allocations = new IdentityHashMap<>();
	private static final IdentityHashMap<Bitmap, Integer> references = new IdentityHashMap<>();
	private static long bytes, reserved;
	private BackPreviewBudget() {}
	public static long limit(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (manager == null) return LOW_MEMORY_BYTES;
		return manager.isLowRamDevice() ? LOW_MEMORY_BYTES
				: Math.min(NORMAL_BYTES, Math.max(LOW_MEMORY_BYTES, manager.getMemoryClass() * 1024L * 1024 / 6));
	}
	public static long usedBytes() { return bytes + reserved; }
	public static int[] dimensions(int width, int height, long maxBytes) {
		if (width <= 0 || height <= 0 || maxBytes < 4) return null;
		double scale = Math.min(1d, Math.sqrt(maxBytes / (4d * width * height)));
		int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
		if ((long) w * h * 4 > maxBytes) h = (int) (maxBytes / (4L * w));
		return h > 0 ? new int[] {w, h} : null;
	}
	public static Bitmap capture(View view, long maxBytes, int background) {
		return capture(view, maxBytes, background, false);
	}
	/** Explicit WebView-only path, called after a generation-checked visual callback. */
	public static Bitmap captureWeb(android.webkit.WebView view, long maxBytes, int background) {
		return capture(view, maxBytes, background, true);
	}
	private static Bitmap capture(View view, long maxBytes, int background, boolean web) {
		long allowance = Math.min(maxBytes, Math.max(0, limit(view.getContext()) - usedBytes()));
		int[] size = dimensions(view.getWidth(), view.getHeight(), allowance);
		if (size == null || size[0] < view.getWidth() * MIN_LINEAR_SCALE || size[1] < view.getHeight() * MIN_LINEAR_SCALE
				|| (!web && PredictiveBackMotion.hasExternalSurface(view))) return null;
		long reservation = 4L * size[0] * size[1]; reserved += reservation;
		Bitmap image = null;
		try {
			image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
			long actual = image.getAllocationByteCount();
			if (bytes + reserved - reservation + actual > limit(view.getContext())) { image.recycle(); return null; }
			Canvas canvas = new Canvas(image); canvas.drawColor(background);
			canvas.scale(size[0] / (float) view.getWidth(), size[1] / (float) view.getHeight());
			if (web) canvas.translate(-view.getScrollX(), -view.getScrollY());
			view.draw(canvas);
			allocations.put(image, actual); references.put(image, 1); bytes += actual; return image;
		} catch (RuntimeException | OutOfMemoryError error) {
			if (image != null) image.recycle(); return null;
		} finally { reserved -= reservation; }
	}
	/** Lease an existing ledger image. Unmanaged bitmaps retain their caller's ownership. */
	public static boolean retain(Bitmap image) {
		Integer count = references.get(image);
		if (count == null || image == null || image.isRecycled()) return false;
		references.put(image, count + 1); return true;
	}
	public static void release(Bitmap image) {
		if (image == null) return;
		Integer count = references.get(image);
		if (count != null && count > 1) { references.put(image, count - 1); return; }
		references.remove(image);
		Long allocation = allocations.remove(image); if (allocation != null) bytes -= allocation;
		if (!image.isRecycled()) image.recycle();
	}
}
