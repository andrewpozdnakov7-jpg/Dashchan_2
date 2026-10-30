package com.mishiranu.dashchan.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

/** UI-thread-only, process-local ledger shared by page and gallery predictive snapshots. */
public final class BackPreviewBudget {
	public static final long NORMAL_BYTES = 48L * 1024 * 1024, LOW_MEMORY_BYTES = 12L * 1024 * 1024;
	public static final long PREDICTIVE_BYTES = 256L * 1024 * 1024;
	public static final float MIN_LINEAR_SCALE = .35f;
	private static final IdentityHashMap<Bitmap, Long> allocations = new IdentityHashMap<>();
	private static final IdentityHashMap<Bitmap, Integer> references = new IdentityHashMap<>();
	private static long bytes, reserved;
	/** Evicts cache ownership only. A gesture's separate lease keeps its pixels alive. */
	public interface Cache { boolean evictOldest(); }
	private static final WeakHashMap<Cache, Boolean> caches = new WeakHashMap<>();
	private BackPreviewBudget() {}
	public static long limit(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (manager == null) return LOW_MEMORY_BYTES;
		return limitForMemoryClass(manager.getMemoryClass(), manager.isLowRamDevice(), InterfaceMotion.isPredictiveBackEnabled());
	}
	static long limitForMemoryClass(int memoryClass, boolean lowRam, boolean predictive) {
		if (memoryClass <= 0) return LOW_MEMORY_BYTES;
		long memoryBytes = memoryClass * 1024L * 1024;
		if (predictive) return Math.min(PREDICTIVE_BYTES, memoryBytes / 2);
		return lowRam ? LOW_MEMORY_BYTES : Math.min(NORMAL_BYTES, Math.max(LOW_MEMORY_BYTES, memoryBytes / 6));
	}
	public static void registerCache(Cache cache) { caches.put(cache, Boolean.TRUE); }
	public static void unregisterCache(Cache cache) { caches.remove(cache); }
	public static void trimCaches() {
		for (Cache cache : new ArrayList<>(caches.keySet())) while (cache.evictOldest()) {}
	}
	static void makeRoom(Context context, long requested) {
		long cap = limit(context);
		while (usedBytes() > cap - requested) {
			boolean evicted = false;
			for (Cache cache : new ArrayList<>(caches.keySet())) {
				if (cache.evictOldest()) { evicted = true; break; }
			}
			if (!evicted) break;
		}
	}
	private static boolean memoryLow(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (manager == null) return false;
		ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
		manager.getMemoryInfo(info);
		return info.lowMemory;
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
		if (!web && PredictiveBackMotion.hasExternalSurface(view)) return null;
		if (memoryLow(view.getContext())) { trimCaches(); return null; }
		// Reclaim only the bytes needed by this capture, not an arbitrary fraction of the cache.
		int[] requestedSize = dimensions(view.getWidth(), view.getHeight(), Math.min(maxBytes, limit(view.getContext())));
		if (requestedSize == null) return null;
		makeRoom(view.getContext(), 4L * requestedSize[0] * requestedSize[1]);
		long allowance = Math.min(maxBytes, Math.max(0, limit(view.getContext()) - usedBytes()));
		int[] size = dimensions(view.getWidth(), view.getHeight(), allowance);
		if (size == null || size[0] < view.getWidth() * MIN_LINEAR_SCALE || size[1] < view.getHeight() * MIN_LINEAR_SCALE) return null;
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
			if (image != null) image.recycle();
			trimCaches();
			return null;
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
