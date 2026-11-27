package com.mishiranu.dashchan.content;

import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.util.Pair;
import androidx.annotation.NonNull;
import chan.content.Chan;
import chan.content.ChanResourceAccess;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.CacheDirectory;
import com.mishiranu.dashchan.util.Hasher;
import com.mishiranu.dashchan.util.LruCache;
import com.mishiranu.dashchan.util.MimeTypes;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class CacheManager implements Runnable {
	private static final int MAX_THUMBNAILS_PART = 1;
	private static final int MAX_MEDIA_PART = 4;

	private static final float TRIM_FACTOR = 0.3f;

	private static final CacheManager INSTANCE = new CacheManager();

	public static CacheManager getInstance() {
		return INSTANCE;
	}

	private CacheManager() {
		if (MainApplication.getInstance().isMainProcess()) {
			removeExpiredShareFiles(getExternalTempDirectory());
			removeExpiredShareFiles(FileProvider.getSharedAttachmentDirectory());
			syncCache();
			IntentFilter intentFilter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
			intentFilter.addDataScheme("file");
			AndroidUtils.registerSystemReceiver(MainApplication.getInstance(),
					AndroidUtils.createReceiver((r, c, i) -> {
						synchronized (directoryLocker) {
							externalCacheDirectory = null;
							externalTempDirectory = null;
							thumbnailsWriteDirectory = null;
							mediaWriteDirectory = null;
						}
						syncCache();
					}), intentFilter);
			new Thread(this, "CacheManagerWorker").start();
		}
	}

	private final LinkedBlockingQueue<CacheItem> cacheItemsToDelete = new LinkedBlockingQueue<>();

	@Override
	public void run() {
		while (true) {
			CacheItem cacheItem;
			try {
				cacheItem = cacheItemsToDelete.take();
			} catch (InterruptedException e) {
				return;
			}
			File file = cacheItem.file;
			LinkedHashMap<String, CacheItem> items = getCacheItems(cacheItem.type);
			synchronized (items) {
				// An eviction queued before a replacement/scan must not delete the replacement.
				boolean exists = file.exists();
				if (exists && (file.length() != cacheItem.length
						|| file.lastModified() != cacheItem.lastModified)) continue;
				if (!exists || file.delete()) {
					CacheItem removed = items.remove(cacheItem.key);
					if (removed != null) modifyCacheSize(cacheItem.type, -removed.length);
					getCacheScan(cacheItem.type).record(cacheItem.key, null);
				}
			}
		}
	}

	private volatile CountDownLatch cacheBuildingLatch;
	private volatile boolean cacheScanFailed;
	private final Object scanLock = new Object();

	private static class CacheItem {
		public enum Type {
			THUMBNAILS("thumbnails"), MEDIA("media");

			final String directoryName;

			Type(String directoryName) { this.directoryName = directoryName; }
		}

		public final File file;
		public final String key;
		public final long length;
		public long lastModified;
		public final Type type;

		public CacheItem(File file, Type type) {
			this.file = file;
			key = cacheKey(file);
			length = file.length();
			lastModified = file.lastModified();
			this.type = type;
		}

		@NonNull
		@Override
		public String toString() {
			return "CacheItem [\"" + file.getName() + "\", " + length + "]";
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (o instanceof CacheItem) {
				CacheItem co = (CacheItem) o;
				return type == co.type && key.equals(co.key);
			}
			return false;
		}

		@Override
		public int hashCode() {
			int prime = 31;
			int result = 1;
			result = prime * result + type.hashCode();
			result = prime * result + key.hashCode();
			return result;
		}
	}

	private static String cacheKey(File file) {
		return file.getParent() + File.separator + file.getName().toLowerCase(Locale.US);
	}

	private static final Comparator<CacheItem> SORT_BY_DATE_COMPARATOR =
			(lhs, rhs) -> ((Long) lhs.lastModified).compareTo(rhs.lastModified);

	private final LinkedHashMap<String, CacheItem> thumbnailsCache = new LinkedHashMap<>();
	private final LinkedHashMap<String, CacheItem> mediaCache = new LinkedHashMap<>();
	private final CacheScanChanges<CacheItem> thumbnailsScan = new CacheScanChanges<>();
	private final CacheScanChanges<CacheItem> mediaScan = new CacheScanChanges<>();

	private volatile long thumbnailsCacheSize;
	private volatile long mediaCacheSize;

	private void fillCache(LinkedHashMap<String, CacheItem> cacheItems, CacheItem.Type type)
			throws IOException {
		File writeDirectory = getWritableCacheDirectory(type);
		ArrayList<CacheItem> cacheItemsList = new ArrayList<>();
		boolean scanned = false;
		for (File directory : getCacheDirectories(type)) {
			File[] files = directory.listFiles();
			if (files == null) {
				if (directory.equals(writeDirectory)) throw new IOException("Cache directory scan failed");
				continue;
			}
			scanned = true;
			for (File file : files) {
				if (file.isFile()) cacheItemsList.add(new CacheItem(file, type));
			}
		}
		if (!scanned) throw new IOException("Cache directory unavailable");
		Collections.sort(cacheItemsList, SORT_BY_DATE_COMPARATOR);
		for (CacheItem cacheItem : cacheItemsList) {
			cacheItems.put(cacheItem.key, cacheItem);
		}
	}

	private void syncCache() {
		final CountDownLatch latch = new CountDownLatch(1);
		final long thumbnailsToken;
		final long mediaToken;
		synchronized (scanLock) {
			cacheBuildingLatch = latch;
			cacheScanFailed = false;
			synchronized (thumbnailsCache) { thumbnailsToken = thumbnailsScan.begin(); }
			synchronized (mediaCache) { mediaToken = mediaScan.begin(); }
		}
		new Thread(() -> {
			boolean success = false;
			try {
				// No shared index lock during directory traversal, stat calls or sorting.
				LinkedHashMap<String, CacheItem> thumbnails = new LinkedHashMap<>();
				LinkedHashMap<String, CacheItem> media = new LinkedHashMap<>();
				fillCache(thumbnails, CacheItem.Type.THUMBNAILS);
				fillCache(media, CacheItem.Type.MEDIA);
				synchronized (scanLock) {
					if (cacheBuildingLatch == latch) {
						synchronized (thumbnailsCache) {
							thumbnailsScan.merge(thumbnailsToken, thumbnails);
							thumbnailsCache.clear();
							thumbnailsCache.putAll(thumbnails);
							thumbnailsCacheSize = sumCacheSize(thumbnailsCache);
						}
						synchronized (mediaCache) {
							mediaScan.merge(mediaToken, media);
							mediaCache.clear();
							mediaCache.putAll(media);
							mediaCacheSize = sumCacheSize(mediaCache);
						}
						success = true;
					}
				}
			} catch (IOException | RuntimeException e) {
				android.util.Log.w("CacheManager", "cache_index_failed " + e.getClass().getSimpleName());
			} finally {
				synchronized (scanLock) {
					if (cacheBuildingLatch == latch && !success) {
						cacheScanFailed = true;
						synchronized (thumbnailsCache) { thumbnailsScan.fail(thumbnailsToken); }
						synchronized (mediaCache) { mediaScan.fail(mediaToken); }
					}
					latch.countDown();
				}
			}
			if (success) cleanupAsync(true, true);
		}, "CacheIndexScan").start();
	}

	private static long sumCacheSize(LinkedHashMap<String, CacheItem> items) {
		long size = 0;
		for (CacheItem item : items.values()) size += item.length;
		return size;
	}

	private void cleanupAsync(boolean thumbnails, boolean media) {
		int maxCache = MAX_THUMBNAILS_PART + MAX_MEDIA_PART;
		long maxCacheSize = Preferences.getCacheSize() * 1000L * 1000L;
		ArrayList<CacheItem> cleanupCacheItems = null;
		if (thumbnails) {
			synchronized (thumbnailsCache) {
				long maxSize = MAX_THUMBNAILS_PART * maxCacheSize / maxCache;
				if (!thumbnailsScan.isBuilding() && !cacheScanFailed && thumbnailsCacheSize > maxSize) {
					if (cleanupCacheItems == null) {
						cleanupCacheItems = new ArrayList<>();
					}
					thumbnailsCacheSize = obtainCacheItemsToCleanup(cleanupCacheItems, thumbnailsCache,
							thumbnailsCacheSize, maxSize, null);
				}
			}
		}
		if (media) {
			synchronized (mediaCache) {
				long maxSize = MAX_MEDIA_PART * maxCacheSize / maxCache;
				if (!mediaScan.isBuilding() && !cacheScanFailed && mediaCacheSize > maxSize) {
					if (cleanupCacheItems == null) {
						cleanupCacheItems = new ArrayList<>();
					}
					mediaCacheSize = obtainCacheItemsToCleanup(cleanupCacheItems, mediaCache,
							mediaCacheSize, maxSize, null);
				}
			}
		}
		if (cleanupCacheItems != null && cleanupCacheItems.size() > 0) {
			// Start handling
			cacheItemsToDelete.addAll(cleanupCacheItems);
		}
	}

	private long obtainCacheItemsToCleanup(ArrayList<CacheItem> cleanupCacheItems,
			LinkedHashMap<String, CacheItem> cacheItems, long size, long maxSize, DeleteCondition deleteCondition) {
		long trimAmount = (long) (TRIM_FACTOR * maxSize);
		long deleteAmount = size - maxSize + trimAmount;
		Iterator<CacheItem> iterator = cacheItems.values().iterator();
		while (iterator.hasNext() && deleteAmount > 0) {
			CacheItem cacheItem = iterator.next();
			if (deleteCondition == null || deleteCondition.allowDeleteCacheItem(cacheItem)) {
				deleteAmount -= cacheItem.length;
				size -= cacheItem.length;
				iterator.remove();
				cleanupCacheItems.add(cacheItem);
			}
		}
		return size;
	}

	private interface DeleteCondition {
		boolean allowDeleteCacheItem(CacheItem cacheItem);
	}

	// Only the explicit background cache-clearing task may wait for a complete index.
	private void awaitCacheScanForErase() throws InterruptedException {
		if (com.mishiranu.dashchan.util.ConcurrentUtils.isMain()) {
			throw new IllegalStateException("Cache clearing must run off the UI thread");
		}
		while (true) {
			CountDownLatch latch = cacheBuildingLatch;
			if (latch != null) latch.await();
			if (latch == cacheBuildingLatch) return;
		}
	}

	private CacheScanChanges<CacheItem> getCacheScan(CacheItem.Type type) {
		return type == CacheItem.Type.THUMBNAILS ? thumbnailsScan : mediaScan;
	}

	private LinkedHashMap<String, CacheItem> getCacheItems(CacheItem.Type type) {
		switch (type) {
			case THUMBNAILS: {
				return thumbnailsCache;
			}
			case MEDIA: {
				return mediaCache;
			}
		}
		throw new RuntimeException("Unknown cache type");
	}

	private void modifyCacheSize(CacheItem.Type type, long lengthDelta) {
		switch (type) {
			case THUMBNAILS: {
				thumbnailsCacheSize += lengthDelta;
				break;
			}
			case MEDIA: {
				mediaCacheSize += lengthDelta;
				break;
			}
		}
	}

	private boolean isFileExistsInCache(File file, CacheItem.Type type) {
		LinkedHashMap<String, CacheItem> cacheItems = getCacheItems(type);
		synchronized (cacheItems) {
			// A known file must remain readable even before the full index is ready.
			if (getCacheScan(type).isBuilding() || cacheScanFailed) return file.isFile();
			CacheItem cacheItem = cacheItems.get(cacheKey(file));
			if (cacheItem != null && !file.isFile()) {
				cacheItems.remove(cacheItem.key);
				modifyCacheSize(type, -cacheItem.length);
				cacheItem = null;
			}
			return cacheItem != null;
		}
	}

	private void updateCachedFileLastModified(File file, CacheItem.Type type) {
		LinkedHashMap<String, CacheItem> cacheItems = getCacheItems(type);
		synchronized (cacheItems) {
			String key = cacheKey(file);
			CacheItem cacheItem = cacheItems.remove(key);
			if (cacheItem == null && (getCacheScan(type).isBuilding() || cacheScanFailed) && file.isFile()) {
				cacheItem = new CacheItem(file, type);
				modifyCacheSize(type, cacheItem.length);
			}
			if (cacheItem != null) {
				if (file.exists()) {
					long lastModified = System.currentTimeMillis();
					file.setLastModified(lastModified);
					cacheItem.lastModified = lastModified;
					cacheItems.put(key, cacheItem);
					getCacheScan(type).record(key, cacheItem);
				} else {
					modifyCacheSize(type, -cacheItem.length);
					getCacheScan(type).record(key, null);
				}
			}
			if (!cacheItems.containsKey(key)) getCacheScan(type).record(key, null);
		}
	}

	private void validateNewCachedFile(File file, CacheItem.Type type, boolean success) {
		LinkedHashMap<String, CacheItem> cacheItems = getCacheItems(type);
		synchronized (cacheItems) {
			success &= file.isFile();
			String key = cacheKey(file);
			long lengthDelta = 0L;
			CacheItem cacheItem = cacheItems.remove(key);
			if (cacheItem != null) {
				lengthDelta = -cacheItem.length;
			}
			if (success) {
				cacheItem = new CacheItem(file, type);
				cacheItems.put(key, cacheItem);
				lengthDelta += cacheItem.length;
			}
			getCacheScan(type).record(key, success ? cacheItem : null);
			modifyCacheSize(type, lengthDelta);
			if (success) {
				cleanupAsync(type == CacheItem.Type.THUMBNAILS, type == CacheItem.Type.MEDIA);
			}
		}
	}

	public boolean isCacheAvailable() {
		return getMediaDirectory() != null;
	}

	/** Non-blocking snapshot: -1 while scanning, -2 if the index could not be built. */
	public long getCacheSize() {
		CountDownLatch latch = cacheBuildingLatch;
		if (latch != null && latch.getCount() > 0) return -1L;
		if (cacheScanFailed) return -2L;
		return thumbnailsCacheSize + mediaCacheSize;
	}

	public File getMediaDirectory() {
		return getWritableCacheDirectory(CacheItem.Type.MEDIA);
	}

	public List<File> getMediaDirectories() {
		return getCacheDirectories(CacheItem.Type.MEDIA);
	}

	private File getCacheDirectory(CacheItem.Type type, boolean internal) {
		File root = internal ? getInternalCacheDirectory() : getExternalCacheDirectory();
		return CacheDirectory.child(root, type.directoryName);
	}

	private List<File> getCacheDirectories(CacheItem.Type type) {
		ArrayList<File> directories = new ArrayList<>(2);
		File external = getCacheDirectory(type, false);
		if (external != null) directories.add(external);
		File internal = getCacheDirectory(type, true);
		if (internal != null && !internal.equals(external)) directories.add(internal);
		return directories;
	}

	private File getWritableCacheDirectory(CacheItem.Type type) {
		synchronized (directoryLocker) {
			boolean media = type == CacheItem.Type.MEDIA;
			File current = media ? mediaWriteDirectory : thumbnailsWriteDirectory;
			if (current != null && current.isDirectory() && current.canWrite()) return current;
			File external = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
					? getCacheDirectory(type, false) : null;
			File internal = getCacheDirectory(type, true);
			File directory;
			if (CacheDirectory.isWritable(external)) {
				directory = external;
			} else if (CacheDirectory.isWritable(internal)) {
				directory = internal;
				android.util.Log.i("CacheManager", "internal_cache_selected type=" + type);
			} else {
				directory = null;
			}
			if (media) {
				mediaWriteDirectory = directory;
			} else {
				thumbnailsWriteDirectory = directory;
			}
			return directory;
		}
	}

	private File getCachedOrWritableFile(String name, CacheItem.Type type) {
		if (name == null) return null;
		File writeDirectory = getWritableCacheDirectory(type);
		File destination = CacheDirectory.child(writeDirectory, name);
		if (destination != null && isFileExistsInCache(destination, type)) return destination;
		for (File directory : getCacheDirectories(type)) {
			if (directory.equals(writeDirectory)) continue;
			File file = new File(directory, name);
			if (isFileExistsInCache(file, type) && file.canRead()) return file;
		}
		return destination;
	}

	public File getMediaFile(Uri uri, boolean touch) {
		// Gallery-local sources already identify a file. They are not remote cache keys;
		// browsing them must not touch the eviction order or create another cached copy.
		if (uri != null && "file".equals(uri.getScheme())) {
			String path = uri.getPath();
			return path != null ? new File(path) : null;
		}
		File file = getCachedOrWritableFile(getCachedFileKey(uri), CacheItem.Type.MEDIA);
		if (touch && file != null) updateCachedFileLastModified(file, CacheItem.Type.MEDIA);
		return file;
	}

	public File getMediaFileForWrite(Uri uri) {
		String key = getCachedFileKey(uri);
		return key != null ? CacheDirectory.child(getMediaDirectory(), key) : null;
	}

	/** Completed-index check for the browser; does not wait for scans or renew an entry. */
	boolean isCompletedMediaFile(File file, long length, long modified) {
		synchronized (mediaCache) {
			CacheItem item = mediaCache.get(cacheKey(file));
			return item != null && item.length == length && item.lastModified == modified;
		}
	}

	private void eraseCache(LinkedHashMap<String, CacheItem> cacheItems, CacheItem.Type type)
			throws InterruptedException {
		// This explicit operation runs on the clearing worker. Rescan its directory
		// so a failed/startup index never silently leaves unindexed files behind.
		LinkedHashMap<String, CacheItem> snapshot = new LinkedHashMap<>();
		try {
			fillCache(snapshot, type);
		} catch (IOException e) {
			cacheScanFailed = true;
			return;
		}
		cacheItems.clear();
		cacheItems.putAll(snapshot);
		Iterator<CacheItem> iterator = cacheItems.values().iterator();
		while (iterator.hasNext()) {
			if (Thread.interrupted()) throw new InterruptedException();
			CacheItem cacheItem = iterator.next();
			File file = cacheItem.file;
			if (file.delete() || !file.exists()) {
				iterator.remove();
				getCacheScan(type).record(cacheItem.key, null);
			} else {
				getCacheScan(type).record(cacheItem.key, cacheItem);
			}
		}
	}

	public void eraseThumbnailsCache() throws InterruptedException {
		awaitCacheScanForErase();
		synchronized (thumbnailsCache) {
			try {
				eraseCache(thumbnailsCache, CacheItem.Type.THUMBNAILS);
			} finally {
				thumbnailsCacheSize = sumCacheSize(thumbnailsCache);
			}
		}
	}

	public void eraseMediaCache() throws InterruptedException {
		awaitCacheScanForErase();
		synchronized (mediaCache) {
			try {
				eraseCache(mediaCache, CacheItem.Type.MEDIA);
			} finally {
				mediaCacheSize = sumCacheSize(mediaCache);
			}
		}
	}

	private final LruCache<String, String> cachedFileKeys = new LruCache<>(1000);

	public String getCachedFileKey(Uri uri) {
		if (uri != null) {
			String data;
			String scheme = uri.getScheme();
			if ("data".equals(scheme)) {
				String uriString = uri.toString();
				int index = uriString.indexOf("base64,");
				if (index >= 0) {
					data = uriString.substring(index + 7);
				} else {
					data = uriString;
				}
			} else if ("chan".equals(scheme)) {
				data = ChanResourceAccess.canonicalize(uri).toString();
			} else if (LocalArchiveManager.RESOURCE_SCHEME.equals(scheme)) {
				data = uri.toString();
			} else {
				Chan chan = Chan.getPreferred(null, uri);
				String path = uri.getPath();
				String query = uri.getQuery();
				String fragment = uri.getFragment();
				StringBuilder dataBuilder = new StringBuilder();
				if (chan.name != null) {
					dataBuilder.append(chan.name);
				}
				dataBuilder.append(path);
				if (query != null) {
					dataBuilder.append('?').append(query);
				}
				// HTTP fragments normally do not identify different downloaded files. Internal fragments with this
				// prefix carry a cache revision for content that must be transformed before it is displayed.
				if (fragment != null && fragment.startsWith("slooop-cache-")) {
					dataBuilder.append('#').append(fragment);
				}
				data = dataBuilder.toString();
			}
			LruCache<String, String> cachedFileKeys = this.cachedFileKeys;
			synchronized (cachedFileKeys) {
				String hash = cachedFileKeys.get(data);
				if (hash != null) {
					return hash;
				}
			}
			String hash = StringUtils.formatHex(Hasher.getInstanceSha256().calculate(data));
			synchronized (cachedFileKeys) {
				cachedFileKeys.put(data, hash);
			}
			return hash;
		}
		return null;
	}

	public boolean cancelCachedMediaBusy(File file) {
		if (cacheItemsToDelete.remove(new CacheItem(file, CacheItem.Type.MEDIA))) {
			file.delete();
			validateNewCachedFile(file, CacheItem.Type.MEDIA, false);
			return true;
		}
		return false;
	}

	public void handleDownloadedFile(File file, boolean success) {
		File directory = file.getParentFile();
		if (directory != null) {
			CacheItem.Type type = null;
			if (getCacheDirectories(CacheItem.Type.THUMBNAILS).contains(directory)) {
				type = CacheItem.Type.THUMBNAILS;
			} else if (getCacheDirectories(CacheItem.Type.MEDIA).contains(directory)) {
				type = CacheItem.Type.MEDIA;
			}
			if (type != null) {
				validateNewCachedFile(file, type, success);
			}
		}
	}

	public File getThumbnailFile(String thumbnailKey) {
		return getCachedOrWritableFile(thumbnailKey, CacheItem.Type.THUMBNAILS);
	}

	public Bitmap loadThumbnailExternal(String thumbnailKey) {
		return loadThumbnailExternal(thumbnailKey,
				ThumbnailDecoder.targetSize(MainApplication.getInstance().getResources()));
	}

	public Bitmap loadThumbnailExternal(String thumbnailKey, int targetSize) {
		File file = getThumbnailFile(thumbnailKey);
		if (file == null) {
			return null;
		}
		if (!isFileExistsInCache(file, CacheItem.Type.THUMBNAILS)) {
			return null;
		}
		Bitmap bitmap;
		try {
			bitmap = ThumbnailDecoder.decode(file, targetSize);
			if (bitmap == null) {
				if (!Thread.currentThread().isInterrupted()) {
					file.delete();
					validateNewCachedFile(file, CacheItem.Type.THUMBNAILS, false);
				}
				return null;
			}
			updateCachedFileLastModified(file, CacheItem.Type.THUMBNAILS);
			return bitmap;
		} catch (RuntimeException | OutOfMemoryError e) {
			return null;
		}
	}

	public void storeThumbnailExternal(String thumbnailKey, Bitmap data) {
		File directory = getWritableCacheDirectory(CacheItem.Type.THUMBNAILS);
		if (directory == null) {
			return;
		}
		boolean success = false;
		File file = new File(directory, thumbnailKey);
		try (FileOutputStream output = new FileOutputStream(file)) {
			data.compress(Bitmap.CompressFormat.PNG, 100, output);
			success = true;
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			validateNewCachedFile(file, CacheItem.Type.THUMBNAILS, success);
		}
	}

	private final Object directoryLocker = new Object();

	private volatile File externalCacheDirectory;
	private volatile File externalTempDirectory;
	private volatile File internalCacheDirectory;
	private File thumbnailsWriteDirectory;
	private File mediaWriteDirectory;

	private File getExternalCacheDirectory() {
		if (externalCacheDirectory == null) {
			synchronized (directoryLocker) {
				if (externalCacheDirectory == null) {
					externalCacheDirectory = MainApplication.getInstance().getExternalCacheDir();
				}
			}
		}
		return externalCacheDirectory;
	}

	private File getInternalCacheDirectory() {
		File directory = internalCacheDirectory;
		if (directory == null) {
			directory = MainApplication.getInstance().getCacheDir();
			internalCacheDirectory = directory;
		}
		return directory;
	}

	private File getExternalTempDirectory() {
		if (externalTempDirectory == null) {
			synchronized (directoryLocker) {
				if (externalTempDirectory == null) {
					externalTempDirectory = MainApplication.getInstance().getExternalCacheDir();
				}
			}
		}
		return externalTempDirectory;
	}

	public File getInternalCacheFile(String fileName) {
		File cacheDirectory = MainApplication.getInstance().getCacheDir();
		if (cacheDirectory != null) {
			return new File(cacheDirectory, fileName);
		}
		return null;
	}

	private static final String GALLERY_SHARE_FILE_NAME_START = FileProvider.GALLERY_SHARE_FILE_NAME_START;

	private static void removeExpiredShareFiles(File tempDirectory) {
		if (tempDirectory == null) {
			return;
		}
		long time = System.currentTimeMillis();
		File[] files = tempDirectory.listFiles();
		if (files != null) {
			for (File tempFile : files) {
				if (tempFile.getName().startsWith(GALLERY_SHARE_FILE_NAME_START)) {
					boolean delete = tempFile.lastModified() + 60 * 60 * 1000 < time; // 1 hour
					if (delete) {
						tempFile.delete();
					}
				}
			}
		}
	}

	public Pair<Uri, String> prepareFileForShare(File file, String fileName) {
		return prepareFileForShare(file, fileName, getExternalTempDirectory(), false);
	}

	public Pair<Uri, String> prepareAttachmentForShare(File file, String fileName) {
		File directory = FileProvider.getSharedAttachmentDirectory();
		if (directory == null || !directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
			return null;
		}
		return prepareFileForShare(file, fileName, directory, true);
	}

	private Pair<Uri, String> prepareFileForShare(File file, String fileName, File tempDirectory, boolean attachment) {
		if (tempDirectory == null) {
			return null;
		}
		String extension = StringUtils.getFileExtension(fileName);
		String mimeType = MimeTypes.forExtension(extension);
		if (mimeType == null) {
			mimeType = "image/jpeg";
			extension = "jpg";
		}
		removeExpiredShareFiles(tempDirectory);
		fileName = GALLERY_SHARE_FILE_NAME_START + java.util.UUID.randomUUID() + "." + extension;
		File shareFile = new File(tempDirectory, fileName);
		if (!ShareFileCopy.copy(file, shareFile)) {
			return null;
		}
		Uri uri;
		try {
			uri = attachment ? FileProvider.sharedAttachmentUri(shareFile)
					: FileProvider.convertShareFile(tempDirectory, shareFile, mimeType);
		} catch (IOException e) {
			uri = null;
		}
		if (uri == null) {
			shareFile.delete();
			return null;
		}
		return new Pair<>(uri, mimeType);
	}
}
