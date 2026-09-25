package com.mishiranu.dashchan.content;

import android.graphics.Bitmap;
import android.content.ComponentCallbacks2;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.WeightedLruCache;
import com.mishiranu.dashchan.widget.AttachmentView;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;

public class ImageLoader {
	private static final int CONNECT_TIMEOUT = 10000;
	private static final int READ_TIMEOUT = 5000;

	private static final ImageLoader INSTANCE = new ImageLoader();

	public static ImageLoader getInstance() {
		return INSTANCE;
	}

	private ImageLoader() {}

	private final HashMap<String, LoaderTask> loaderTasks = new HashMap<>();
	private final MissingImageCache notFoundMap =
			new MissingImageCache(1024, 5 * 60 * 1000L, SystemClock::elapsedRealtime);

	private final HashMap<String, Executor> executors = new HashMap<>();

	private Executor getExecutor(String chanName) {
		Executor executor = executors.get(chanName);
		if (executor == null) {
			executor = ConcurrentUtils.newThreadPool(3, 3, 0, "ImageLoader", chanName);
			executors.put(chanName, executor);
		}
		return executor;
	}

	private interface TaskCallback {
		void onTaskFinished(String key, Bitmap bitmap, boolean error);
	}

	private class LoaderTask extends HttpHolderTask<Void, Bitmap> {
		public final Uri uri;
		public final Chan chan;
		public final String key;
		public final String memoryKey;
		public final int targetSize;
		public final boolean fromCacheOnly;

		public final HashSet<TaskCallback> callbacks = new HashSet<>();
		private final Runnable startRunnable = this::start;

		private void start() {
			if (!isCancelled()) execute(getExecutor(chan.name));
		}

		private boolean notFound;

		public LoaderTask(Uri uri, Chan chan, String key, String memoryKey, int targetSize, boolean fromCacheOnly) {
			super(chan);
			this.uri = uri;
			this.chan = chan;
			this.key = key;
			this.memoryKey = memoryKey;
			this.targetSize = targetSize;
			this.fromCacheOnly = fromCacheOnly;
		}

		@Override
		protected Bitmap run(HttpHolder holder) {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			String scheme = uri.getScheme();
			boolean chanScheme = ChanConfiguration.SCHEME_CHAN.equals(scheme);
			boolean dataScheme = "data".equals(scheme);
			boolean localArchiveScheme = LocalArchiveManager.RESOURCE_SCHEME.equals(scheme);
			boolean storeExternal = !chanScheme && !dataScheme;
			Bitmap bitmap = null;
			try {
				bitmap = storeExternal ? CacheManager.getInstance().loadThumbnailExternal(key, targetSize) : null;
				if (isCancelled()) {
					if (bitmap != null) bitmap.recycle();
					return null;
				}
				if (bitmap == null && (!fromCacheOnly || localArchiveScheme)) {
					if (chanScheme) {
						ThumbnailDecoder.LimitedOutput output = new ThumbnailDecoder.LimitedOutput();
						if (!chan.configuration.readResourceUri(uri, output)) {
							throw HttpException.createNotFoundException();
						}
						bitmap = output.decode(targetSize);
					} else if (dataScheme) {
						String data = uri.toString();
						int index = data.indexOf("base64,");
						if (index >= 0) {
							if ((long) data.length() - index - 7 > ThumbnailDecoder.MAX_INPUT_BYTES * 4L / 3 + 4) {
								throw new IOException("Thumbnail data URI exceeds size limit");
							}
							data = data.substring(index + 7);
							byte[] bytes = Base64.decode(data, Base64.DEFAULT);
							if (bytes != null) {
								bitmap = ThumbnailDecoder.decode(bytes, bytes.length, targetSize);
							}
						}
					} else if (localArchiveScheme) {
						try (InputStream input = LocalArchiveManager.openResource(uri)) {
							if (input != null) {
								bitmap = ThumbnailDecoder.decode(input, targetSize);
							}
						}
						if (bitmap == null) {
							throw HttpException.createNotFoundException();
						}
					} else {
						HttpException lastException = null;
						for (int attempt = 0; attempt < 2 && bitmap == null; attempt++) {
							HttpResponse response = null;
							try {
								ChanPerformer.ReadContentResult result = chan.performer.safe()
										.onReadContent(new ChanPerformer.ReadContentData(uri,
												CONNECT_TIMEOUT, READ_TIMEOUT, holder, -1, -1));
								response = result != null ? result.response : null;
								if (response != null) {
									try (InputStream input = response.open()) {
										bitmap = ThumbnailDecoder.decode(input, targetSize);
									} catch (IOException e) {
										throw response.fail(e);
									}
								}
							} catch (ExtensionException e) {
								e.getErrorItemAndHandle();
								return null;
							} catch (HttpException e) {
								lastException = e;
								if (!e.isSocketException() || attempt > 0 || isCancelled()) {
									throw e;
								}
							} finally {
								if (response != null) {
									response.cleanupAndDisconnect();
								}
							}
							if (bitmap == null && lastException != null) {
								SystemClock.sleep(250L);
							}
						}
						if (bitmap == null) {
							if (lastException != null) {
								throw lastException;
							}
							throw new HttpException(ErrorItem.Type.DOWNLOAD, false, false);
						}
					}
					if (isCancelled()) {
						if (bitmap != null) bitmap.recycle();
						return null;
					}
					if (storeExternal && bitmap != null) {
						CacheManager.getInstance().storeThumbnailExternal(key, bitmap);
					}
				}
			} catch (HttpException e) {
				int responseCode = e.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
						responseCode == HttpURLConnection.HTTP_GONE) {
					notFound = true;
				}
			} catch (Exception | OutOfMemoryError e) {
				e.printStackTrace();
			}
			return bitmap;
		}

		@Override
		protected void onComplete(Bitmap bitmap) {
			// FutureTask holds its result: retaining completed tasks would bypass the bitmap cache budget.
			if (loaderTasks.get(memoryKey) == this) loaderTasks.remove(memoryKey);
			if (notFound && !LocalArchiveManager.RESOURCE_SCHEME.equals(uri.getScheme())) {
				notFoundMap.put(memoryKey);
			}
			if (bitmap != null) {
				notFoundMap.remove(memoryKey);
				bitmapCache.put(memoryKey, bitmap);
			}
			ArrayList<TaskCallback> completedCallbacks = new ArrayList<>(callbacks);
			callbacks.clear();
			for (TaskCallback callback : completedCallbacks) {
				callback.onTaskFinished(key, bitmap, !fromCacheOnly);
			}
		}

		@Override
		public void cancel() {
			ConcurrentUtils.HANDLER.removeCallbacks(startRunnable);
			super.cancel();
		}

		@Override
		protected void onCancel(Bitmap bitmap) {
			if (bitmap != null) bitmap.recycle(); // Never published to cache or a View.
			callbacks.clear();
		}
	}

	// Upper bound only: entries are allocated on demand and trimmed under memory pressure.
	private final long bitmapCacheBytes = 200L * 1024 * 1024;
	private final WeightedLruCache<String, Bitmap> bitmapCache = new WeightedLruCache<>(bitmapCacheBytes, 512,
			bitmap -> Math.max(1L, bitmap.getAllocationByteCount()));

	// Legacy pressure levels remain useful on Android 11-13; newer Android reports UI_HIDDEN.
	@SuppressWarnings("deprecation")
	public void onTrimMemory(int level) {
		if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
				level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			bitmapCache.trimToWeight(0);
		} else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
				level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
			bitmapCache.trimToWeight(bitmapCacheBytes / 2);
		}
	}

	public static abstract class Target {
		public String currentKey;
		private String currentTaskKey;

		private final TaskCallback taskCallback = (key, bitmap, error) -> {
			if (key.equals(currentKey)) {
				onResult(key, bitmap, error, false);
			}
		};

		public void onStart() {}
		public abstract void onResult(String key, Bitmap bitmap, boolean error, boolean instantly);
	}

	private interface WrapperCallback<T> {
		void onResult(T target, String key, Bitmap bitmap, boolean error, boolean instantly);
	}

	private static final WrapperCallback<ImageView> WRAPPER_CALLBACK_IMAGE_VIEW =
			(target, key, bitmap, error, instantly) -> {
		if (bitmap != null) {
			target.setImageBitmap(bitmap);
		} else {
			target.setImageDrawable(null);
		}
	};

	private static final WrapperCallback<AttachmentView> WRAPPER_CALLBACK_ATTACHMENT_VIEW =
			AttachmentView::handleLoadedImage;

	private static class WrapperTarget<T> extends Target {
		public final T target;
		public final WrapperCallback<T> callback;

		private WrapperTarget(T target, WrapperCallback<T> callback) {
			this.target = target;
			this.callback = callback;
		}

		@Override
		public void onResult(String key, Bitmap bitmap, boolean error, boolean instantly) {
			callback.onResult(target, key, bitmap, error, instantly);
		}
	}

	private interface DetachCallback {
		void onDetach(View view);
	}

	private static class ViewTarget<T extends View> extends WrapperTarget<T>
			implements Runnable, View.OnAttachStateChangeListener {
		private final DetachCallback detachCallback;

		private ViewTarget(T target, WrapperCallback<T> wrapperCallback, DetachCallback detachCallback) {
			super(target, wrapperCallback);

			this.detachCallback = detachCallback;
			target.addOnAttachStateChangeListener(this);
		}

		@Override
		public void onStart() {
			if (!target.isAttachedToWindow()) {
				onViewDetachedFromWindow(target);
			}
		}

		@Override
		public void run() {
			if (detachCallback != null) {
				detachCallback.onDetach(target);
			}
		}

		@Override
		public void onViewAttachedToWindow(View v) {
			ConcurrentUtils.HANDLER.removeCallbacks(this);
		}

		@Override
		public void onViewDetachedFromWindow(View v) {
			ConcurrentUtils.HANDLER.removeCallbacks(this);
			ConcurrentUtils.HANDLER.postDelayed(this, 2000L);
		}
	}

	private final DetachCallback detachCallback = this::cancel;

	private <T extends View> WrapperTarget<T> getWrapperTarget(T view, WrapperCallback<T> wrapperCallback) {
		@SuppressWarnings("unchecked")
		WrapperTarget<T> wrapperTarget = (WrapperTarget<T>) view.getTag(R.id.tag_image_loader);
		if (wrapperTarget == null && wrapperCallback != null) {
			ViewTarget<T> viewTarget = new ViewTarget<>(view, wrapperCallback, detachCallback);
			view.setTag(R.id.tag_image_loader, viewTarget);
			return viewTarget;
		}
		return wrapperTarget;
	}

	public boolean hasRunningTask(View view) {
		WrapperTarget<?> wrapperTarget = getWrapperTarget(view, null);
		if (wrapperTarget != null && wrapperTarget.currentKey != null) {
			LoaderTask loaderTask = loaderTasks.get(((Target) wrapperTarget).currentTaskKey);
			return loaderTask != null;
		}
		return false;
	}

	public void cancel(Target target) {
		String key = target.currentTaskKey;
		target.currentKey = null;
		target.currentTaskKey = null;
		if (key != null) {
			LoaderTask loaderTask = loaderTasks.get(key);
			if (loaderTask != null) {
				loaderTask.callbacks.remove(target.taskCallback);
				if (loaderTask.callbacks.isEmpty()) {
					loaderTask.cancel();
					loaderTasks.remove(key);
				}
			}
		}
	}

	public void cancel(View view) {
		WrapperTarget<?> wrapperTarget = getWrapperTarget(view, null);
		if (wrapperTarget != null) {
			cancel(wrapperTarget);
		}
	}

	public void loadImage(Chan chan, Uri uri, boolean fromCacheOnly, ImageView target) {
		WrapperTarget<ImageView> wrapperTarget = getWrapperTarget(target, WRAPPER_CALLBACK_IMAGE_VIEW);
		loadImage(chan, uri, null, fromCacheOnly, wrapperTarget);
	}

	public void loadImage(Chan chan, Uri uri, String key, boolean fromCacheOnly, AttachmentView target) {
		WrapperTarget<AttachmentView> wrapperTarget = getWrapperTarget(target, WRAPPER_CALLBACK_ATTACHMENT_VIEW);
		loadImage(chan, uri, key, fromCacheOnly, wrapperTarget);
	}

	public boolean loadImage(Chan chan, Uri uri, String key, boolean fromCacheOnly, Target target) {
		if (key == null) {
			key = CacheManager.getInstance().getCachedFileKey(uri);
		}
		if (key == null) {
			return false;
		}
		int targetSize = ThumbnailDecoder.targetSize(MainApplication.getInstance().getResources());
		// Keep the public attachment/disk key unchanged (also used by reverse image search).
		String memoryKey = chan.name + "\n" + targetSize + "\n" + key;
		boolean mainThread = ConcurrentUtils.isMain();
		if (mainThread) {
			cancel(target);
		}
		Bitmap memoryCachedBitmap;
		if (mainThread) {
			memoryCachedBitmap = bitmapCache.get(memoryKey);
		} else {
			memoryCachedBitmap = ConcurrentUtils.mainGet(() -> bitmapCache.get(memoryKey));
		}
		if (memoryCachedBitmap != null) {
			target.onResult(key, memoryCachedBitmap, false, true);
			return true;
		} else if (!mainThread) {
			// Don't enqueue tasks requested from non-main thread
			target.onResult(key, null, false, true);
			return false;
		}
		// Check "not found" images once per 5 minutes
		if (notFoundMap.contains(memoryKey)) {
			target.onResult(key, null, !fromCacheOnly, true);
			return false;
		}
		target.currentKey = key;
		target.currentTaskKey = memoryKey;
		target.onStart();
		LoaderTask currentLoaderTask = loaderTasks.get(memoryKey);
		boolean startTask = currentLoaderTask == null ||
				currentLoaderTask.fromCacheOnly && !fromCacheOnly;
		LoaderTask registerLoaderTask = currentLoaderTask;
		if (startTask) {
			LoaderTask loaderTask = new LoaderTask(uri, chan, key, memoryKey, targetSize, fromCacheOnly);
			registerLoaderTask = loaderTask;
			if (currentLoaderTask != null) {
				currentLoaderTask.cancel();
				loaderTask.callbacks.addAll(currentLoaderTask.callbacks);
			}
			loaderTasks.put(memoryKey, loaderTask);
			// Debounce without occupying a worker thread. Cache-only work does not need a network delay.
			ConcurrentUtils.HANDLER.postDelayed(loaderTask.startRunnable, fromCacheOnly ? 0L : 500L);
		}
		registerLoaderTask.callbacks.add(target.taskCallback);
		return false;
	}
}
