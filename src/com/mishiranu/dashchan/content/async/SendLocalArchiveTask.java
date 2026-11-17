package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanResourceAccess;
import chan.content.ChanMarkup;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.LocalArchiveManager;
import com.mishiranu.dashchan.content.LocalArchiveReader;
import com.mishiranu.dashchan.content.LocalArchiveMediaPlan;
import com.mishiranu.dashchan.content.LocalArchiveMediaStore;
import com.mishiranu.dashchan.content.LocalArchiveUpdateLease;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.text.HtmlParser;
import com.mishiranu.dashchan.text.WakabaLikeHtmlBuilder;
import com.mishiranu.dashchan.text.style.GainedColorSpan;
import com.mishiranu.dashchan.text.style.HeadingSpan;
import com.mishiranu.dashchan.text.style.ItalicSpan;
import com.mishiranu.dashchan.text.style.LinkSpan;
import com.mishiranu.dashchan.text.style.MediumSpan;
import com.mishiranu.dashchan.text.style.MonospaceSpan;
import com.mishiranu.dashchan.text.style.OverlineSpan;
import com.mishiranu.dashchan.text.style.QuoteSpan;
import com.mishiranu.dashchan.text.style.ScriptSpan;
import com.mishiranu.dashchan.text.style.SpoilerSpan;
import com.mishiranu.dashchan.util.Hasher;
import com.mishiranu.dashchan.util.MimeTypes;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class SendLocalArchiveTask extends ExecutorTask<Integer, SendLocalArchiveTask.Result>
		implements ChanMarkup.MarkupExtra {
	private static final String DIRECTORY_ARCHIVE = LocalArchiveManager.DIRECTORY_ARCHIVE;
	private static final String DIRECTORY_FILES = LocalArchiveManager.DIRECTORY_FILES;
	private static final String DIRECTORY_THUMBNAILS = LocalArchiveManager.DIRECTORY_THUMBNAILS;

	private final Callback callback;
	private final Chan chan;
	private final String boardName;
	private final String threadNumber;
	private final String threadTitle;
	private final Collection<Post> posts;
	private final boolean saveThumbnails;
	private final boolean saveFiles;
	private final boolean createZip;
	private LocalArchiveUpdateLease lease;
	private boolean busy;
	private final String diagnosticId = Long.toHexString(SystemClock.elapsedRealtime()) + "-" + Integer.toHexString(System.identityHashCode(this));
	private static void trace(String id, String event) {
		com.mishiranu.dashchan.media.VideoDiagnostics.recordUi("archive_update id=" + id + " " + event);
	}

	public interface DownloadResult {
		void run(DownloadService.Binder binder);
		default void cancel() {}
	}

	public interface Callback {
		void onLocalArchivationProgressUpdate(int handledPostsCount);
		void onLocalArchivationComplete(DownloadResult result);
	}

	public SendLocalArchiveTask(Callback callback, Chan chan, String boardName, String threadNumber,
			String threadTitle, Collection<Post> posts, boolean saveThumbnails, boolean saveFiles, boolean createZip) {
		this.callback = callback;
		this.chan = chan;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
		this.threadTitle = threadTitle;
		this.posts = posts;
		this.saveThumbnails = saveThumbnails;
		this.saveFiles = saveFiles;
		this.createZip = createZip;
	}

	@Override
	public String getBoardName() {
		return boardName;
	}

	@Override
	public String getThreadNumber() {
		return threadNumber;
	}

	private static class SpanItem {
		public int start;
		public int end;

		public final String openTag;
		public final String closeTag;

		public SpanItem(String openTag, String closeTag) {
			this.openTag = openTag;
			this.closeTag = closeTag;
		}
	}

	private static class SpanEvent {
		public final int offset;
		public final boolean opening;
		public final SpanItem spanItem;
		public final int order;

		public SpanEvent(int offset, boolean opening, SpanItem spanItem, int order) {
			this.offset = offset;
			this.opening = opening;
			this.spanItem = spanItem;
			this.order = order;
		}
	}

	@Override
	protected Result run() {
		trace(diagnosticId, "stage=prepare_start posts=" + posts.size() + " originals=" + saveFiles
				+ " thumbnails=" + saveThumbnails + " zip=" + createZip);
		lease = LocalArchiveUpdateLease.acquire(chan.name + '\0' + boardName + '\0' + threadNumber);
		if (lease == null) { busy = true; trace(diagnosticId, "stage=busy"); return null; }
		boolean prepared = false;
		try {
			if (isCancelled()) return null;
			Result result = prepareArchive();
			prepared = result != null;
			return result;
		} catch (RuntimeException e) {
			trace(diagnosticId, "stage=prepare_failed type=" + e.getClass().getSimpleName());
			return null;
		} finally { if (!prepared) lease.close(); trace(diagnosticId, "stage=prepare_end success=" + prepared + " cancelled=" + isCancelled()); }
	}

	private Result prepareArchive() {
		Chan chan = this.chan;
		String boardName = this.boardName;
		String threadNumber = this.threadNumber;
		Collection<Post> posts = this.posts;
		if (posts.isEmpty()) {
			return null;
		}
		LocalArchiveManager.Item replacedArchive = LocalArchiveManager.findDefaultThreadArchive(
				chan.name, boardName, threadNumber);
		boolean saveThumbnails = this.saveThumbnails;
		boolean saveFiles = this.saveFiles;
		boolean createZip = this.createZip;
		int previousPostsCount = 0;
		HashMap<PostNumber, Post> previousPosts = new HashMap<>();
		HashMap<String, String> previousSources = new HashMap<>();
		HashMap<String, Long> previousSizes = new HashMap<>();
		if (replacedArchive != null) {
			LocalArchiveReader.Archive archive = LocalArchiveReader.read(replacedArchive);
			if (archive != null && chan.name.equals(archive.chanName) && boardName.equals(archive.boardName)
					&& threadNumber.equals(archive.threadNumber)) {
				TreeMap<PostNumber, Post> mergedPosts = new TreeMap<>();
				for (PostItem postItem : archive.postItems.values()) {
					mergedPosts.put(postItem.getPostNumber(), postItem.getPost());
					previousPosts.put(postItem.getPostNumber(), postItem.getPost());
				}
				previousPostsCount = mergedPosts.size();
				for (Post post : posts) {
					mergedPosts.put(post.number, post);
				}
				posts = mergedPosts.values();
				JSONObject manifest = LocalArchiveManager.readManifest(replacedArchive);
				if (manifest != null) {
					JSONObject sources = manifest.optJSONObject("mediaSources");
					if (sources != null) for (java.util.Iterator<String> keys = sources.keys(); keys.hasNext();) {
						String key = keys.next(), value = sources.optString(key, "");
						if (!value.isEmpty()) previousSources.put(key, chan.locator.convert(Uri.parse(value)).toString());
					}
					JSONObject bytes = manifest.optJSONObject("mediaBytes");
					if (bytes != null) for (java.util.Iterator<String> keys = bytes.keys(); keys.hasNext();) {
						String key = keys.next();
						long size = bytes.optLong(key, 0);
						if (size > 0) previousSizes.put(key, size);
					}
					createZip |= manifest.optBoolean("zipRequested");
					saveFiles |= manifest.optBoolean("savedFiles");
					saveThumbnails |= manifest.optBoolean("savedThumbnails");
				}
				createZip |= replacedArchive.hasZip();
			} else {
				replacedArchive = null;
			}
		}
		trace(diagnosticId, "stage=merge previous=" + (replacedArchive != null) + " old_posts=" + previousPostsCount
				+ " merged_posts=" + posts.size() + " receipts=" + previousSizes.size());
		Object[] decodeTo = new Object[2];
		ArrayList<SpanItem> spanItems = new ArrayList<>();
		ArrayList<SpanEvent> spanEvents = new ArrayList<>();
		String resourceDirectory = replacedArchive != null ? LocalArchiveManager.resourceDirectory(replacedArchive) : null;
		String archiveName = chooseArchiveName(resourceDirectory != null ? resourceDirectory
				: chan.name + '-' + boardName + '-' + threadNumber, lease);
		if (resourceDirectory == null) resourceDirectory = archiveName;
		LocalArchiveMediaStore mediaStore = new LocalArchiveMediaStore(replacedArchive, resourceDirectory);
		LocalArchiveMediaPlan media = new LocalArchiveMediaPlan(resourceDirectory, mediaStore, previousSources, previousSizes, mediaStore.existingPaths());
		HashMap<PostNumber, List<Post.Attachment>> attachments = new HashMap<>();
		for (Post post : posts) attachments.put(post.number, mergeAttachments(post, previousPosts.get(post.number), previousSources));
		HashMap<String, String> iconNames = new HashMap<>();
		Hasher hasher = Hasher.getInstanceSha256();
		int totalFilesCount = 0;
		for (Post post : posts) {
			totalFilesCount += attachments.get(post.number).size();
		}
		File cacheFile = CacheManager.getInstance().getInternalCacheFile("archive");
		if (cacheFile == null) {
			return null;
		}
		File archiveFile;
		try {
			archiveFile = File.createTempFile("archive-", ".html", cacheFile.getParentFile());
		} catch (IOException e) {
			return null;
		}
		boolean success = false;
		File preparedZipFile = null;
		ArrayList<DownloadService.DownloadItem> filesToDownload = new ArrayList<>(
				saveFiles ? totalFilesCount : 0);
		ArrayList<DownloadService.DownloadItem> thumbnailsToDownload = new ArrayList<>(
				saveThumbnails ? totalFilesCount : 0);
		try {
			String threadTitle = StringUtils.nullIfEmpty(this.threadTitle);
			if (threadTitle == null) {
				threadTitle = posts.iterator().next().subject;
			}
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(archiveFile), StandardCharsets.UTF_8)) {
			String defaultName = chan.configuration.getDefaultName(boardName);
			WakabaLikeHtmlBuilder htmlBuilder = new WakabaLikeHtmlBuilder(threadTitle,
					boardName, chan.configuration.getBoardTitle(boardName), chan.configuration.getTitle(),
					chan.locator.safe(false).createThreadUri(boardName, threadNumber), posts.size(), totalFilesCount);
			htmlBuilder.setResourceDirectory(resourceDirectory);
			htmlBuilder.writeHeader(writer);
			for (Post post : posts) {
			PostNumber number = post.number;
			Post display = post.deleted && StringUtils.isEmpty(post.comment) && previousPosts.containsKey(number)
					? previousPosts.get(number) : post;
			String name = StringUtils.emptyIfNull(display.name).trim();
			String identifier = display.identifier;
			String tripcode = display.tripcode;
			String capcode = display.capcode;
			String email = display.email;
			String subject = display.subject;
			String comment = display.comment;
			long timestamp = display.timestamp;
			boolean sage = display.isSage();
			boolean originalPoster = display.isOriginalPoster();
			boolean deleted = post.deleted;
			boolean useDefaultName = name.equals(defaultName) || name.isEmpty();
			if (name.isEmpty()) {
				name = defaultName;
			}
			comment = formatComment(comment, decodeTo, spanItems, spanEvents);
			htmlBuilder.addPost(number.toString(), subject, name, identifier, tripcode, capcode, email,
					sage, originalPoster, timestamp, deleted, useDefaultName, comment);
			for (Post.Icon icon : display.icons) {
				if (icon.uri != null && !StringUtils.isEmpty(icon.title)) {
					Uri iconUri = ChanResourceAccess.canonicalize(chan.locator.convert(icon.uri));
					boolean resource = ChanConfiguration.SCHEME_CHAN.equals(iconUri.getScheme());
					// Resource owners are part of identity; generation/query/fragment are not.
					String simpleUri = resource ? iconUri.toString()
							: iconUri.buildUpon().scheme(null).authority(null).build().toString();
					String pathHash = Base64.encodeToString(hasher.calculate(simpleUri), 0, 12,
							Base64.NO_WRAP | Base64.URL_SAFE);
					String iconNameWithoutExtension = "icon-" + pathHash;
					String iconName = iconNames.get(iconNameWithoutExtension);
					if (iconName == null) {
						String extension = null;
						if (resource) {
							ByteArrayOutputStream output = new ByteArrayOutputStream();
							try {
								Chan resourceChan = ChanResourceAccess.resolveResourceChan(chan, iconUri);
								if (resourceChan.configuration.readResourceUri(iconUri, output)) {
									byte[] bytes = output.toByteArray();
									String contentType = URLConnection
											.guessContentTypeFromStream(new ByteArrayInputStream(bytes));
									if (contentType != null) {
										extension = MimeTypes.toExtension(contentType);
									}
								}
							} catch (IOException e) {
								// Ignore
							}
						} else {
							extension = StringUtils.getFileExtension(iconUri.getPath());
						}
						iconName = iconNameWithoutExtension;
						if (!StringUtils.isEmpty(extension)) {
							iconName += "." + extension;
						}
						iconNames.put(iconNameWithoutExtension, iconName);
					}
					String previousIcon = resourcePath(iconUri);
					Post previousPost = previousPosts.get(post.number);
					if (previousIcon == null && previousPost != null) for (Post.Icon oldIcon : previousPost.icons) {
						if (Objects.equals(icon.title, oldIcon.title) && oldIcon.uri != null
								&& iconName.equals(oldIcon.uri.getLastPathSegment())) { previousIcon = resourcePath(oldIcon.uri); break; }
					}
					LocalArchiveMediaPlan.Asset asset = media.register(DIRECTORY_THUMBNAILS, iconName,
							sourceIdentity(iconUri, previousSources), remoteSource(iconUri), previousIcon, 0);
					htmlBuilder.addIcon(asset.path, icon.title);
				}
			}
			for (Post.Attachment attachment : attachments.get(post.number)) {
				if (attachment instanceof Post.Attachment.File) {
					Post.Attachment.File file = (Post.Attachment.File) attachment;
					Uri fileUri = chan.locator.convert(chan.locator.fixRelativeFileUri(file.fileUri));
					Uri thumbnailUri = chan.locator.convert(chan.locator.fixRelativeFileUri(file.thumbnailUri));
					if (fileUri == null) {
						fileUri = thumbnailUri;
					}
					if (fileUri != null) {
						Post.Attachment.File previousFile = findPreviousFile(previousPosts.get(post.number), file, previousSources);
						String oldFilePath = resourcePath(fileUri), oldThumbnailPath = resourcePath(thumbnailUri);
						if (previousFile != null) {
							if (oldFilePath == null) oldFilePath = resourcePath(previousFile.fileUri);
							if (oldThumbnailPath == null) oldThumbnailPath = resourcePath(previousFile.thumbnailUri);
						}
						LocalArchiveMediaPlan.Asset original = media.register(DIRECTORY_FILES,
								chan.locator.createAttachmentFileName(fileUri), sourceIdentity(fileUri, previousSources),
								remoteSource(fileUri), oldFilePath, file.size);
						LocalArchiveMediaPlan.Asset thumbnail = thumbnailUri != null ? media.register(DIRECTORY_THUMBNAILS,
								chan.locator.createAttachmentFileName(thumbnailUri), sourceIdentity(thumbnailUri, previousSources),
								remoteSource(thumbnailUri), oldThumbnailPath, 0) : null;
						String originalName = StringUtils.getNormalizedOriginalName(file.originalName, original.name());
						htmlBuilder.addFile(original.path, thumbnail != null ? thumbnail.path : null, originalName, file.size,
								file.width, file.height);
					}
				}
			}
				htmlBuilder.writePost(writer);
				if (isCancelled()) {
					return null;
				}
				notifyIncrement();
			}
			notifyProgress(progress);
			htmlBuilder.writeFooter(writer);
			}
			ArrayList<LocalArchiveMediaPlan.Asset> requiredMedia = new ArrayList<>();
			JSONObject receipts = new JSONObject();
			for (LocalArchiveMediaPlan.Asset asset : media.assets()) {
				boolean original = LocalArchiveMediaPlan.child(resourceDirectory, DIRECTORY_FILES, asset.path);
				if (!(original ? saveFiles : saveThumbnails)) continue;
				requiredMedia.add(asset);
				if (asset.reused) receipts.put(asset.path, asset.expectedSize);
				trace(diagnosticId, "stage=plan file=" + (requiredMedia.size() - 1) + " kind=" + (original ? "original" : "thumbnail")
						+ " reused=" + asset.reused + " missing=" + asset.missing + " reported_bytes=" + asset.reportedSize
						+ " verified_bytes=" + asset.expectedSize + " source=" + (asset.source == null ? "none"
						: LocalArchiveManager.RESOURCE_SCHEME.equals(Uri.parse(asset.source).getScheme()) ? "zip" : "download"));
				if (asset.source != null) (original ? filesToDownload : thumbnailsToDownload)
						.add(new DownloadService.DownloadItem(chan.name, Uri.parse(asset.source), asset.name(), null, null));
			}
			JSONObject metadata = LocalArchiveManager.createManifest(archiveName, chan.name, boardName,
					threadNumber, threadTitle, posts.size(), totalFilesCount, saveFiles, saveThumbnails);
			metadata.put("resources", resourceDirectory);
			metadata.put("mediaSources", new JSONObject(media.sources()));
			metadata.put("mediaBytes", receipts);
			metadata.put("zipRequested", createZip);
			byte[] manifest = metadata.toString(2).getBytes(StandardCharsets.UTF_8);
			if (createZip) {
				preparedZipFile = createPreparedZip(archiveFile.getParentFile(), archiveName, archiveFile, manifest);
			}
			success = true;
			return new Result(archiveFile, preparedZipFile, archiveName, manifest,
					filesToDownload, thumbnailsToDownload,
					createZip, replacedArchive, previousPostsCount, posts.size(), resourceDirectory, requiredMedia, lease, diagnosticId);
		} catch (IOException | JSONException e) {
			trace(diagnosticId, "stage=prepare_failed type=" + e.getClass().getSimpleName());
			return null;
		} finally {
			if (!success) {
				archiveFile.delete();
				if (preparedZipFile != null) {
					preparedZipFile.delete();
				}
			}
		}
	}

	private static File createPreparedZip(File directory, String archiveName, File archiveFile, byte[] manifest)
			throws IOException {
		File zipFile = File.createTempFile("archive-", ".zip", directory);
		boolean success = false;
		try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zipFile))) {
			output.putNextEntry(new ZipEntry("manifest.json"));
			output.write(manifest);
			output.closeEntry();
			output.putNextEntry(new ZipEntry(archiveName + ".html"));
			try (InputStream input = new FileInputStream(archiveFile)) {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) >= 0) {
					if (count > 0) {
						output.write(buffer, 0, count);
					}
				}
			}
			output.closeEntry();
			success = true;
			return zipFile;
		} finally {
			if (!success) {
				zipFile.delete();
			}
		}
	}

	@Override
	protected void onProgress(Integer value) {
		callback.onLocalArchivationProgressUpdate(value);
	}

	@Override
	protected void onComplete(Result result) {
		if (busy) { callback.onLocalArchivationComplete(binder -> ClickableToast.show(R.string.local_archive_update_busy)); return; }
		callback.onLocalArchivationComplete(result != null ? new PreparedDownload(result) : null);
	}

	@Override protected void onCancel(Result result) {
		trace(diagnosticId, "stage=prepare_cancelled result=" + (result != null));
		if (result != null) discard(result);
		else if (lease != null) lease.close();
	}

	private static void discard(Result result) {
		trace(result.diagnosticId, "stage=prepared_discarded");
		result.archiveFile.delete();
		if (result.preparedZipFile != null) result.preparedZipFile.delete();
		result.lease.close();
	}

	private static final class PreparedDownload implements DownloadResult {
		private final Result result;
		private final AtomicBoolean consumed = new AtomicBoolean();
		PreparedDownload(Result result) { this.result = result; }
		@Override public void cancel() { if (consumed.compareAndSet(false, true)) discard(result); }
		@Override public void run(DownloadService.Binder binder) {
			if (!consumed.compareAndSet(false, true)) return;
			trace(result.diagnosticId, "stage=handoff binder=" + (binder != null));
			if (binder == null) { discard(result); ClickableToast.show(R.string.local_archive_update_failed); return; }
			InputStream archiveInput = null;
			InputStream preparedZipInput = null;
			try {
				archiveInput = new DeleteOnCloseFileInputStream(result.archiveFile);
				if (result.preparedZipFile != null) {
					preparedZipInput = new DeleteOnCloseFileInputStream(result.preparedZipFile);
				}
			} catch (FileNotFoundException e) {
				if (archiveInput != null) {
					try {
						archiveInput.close();
					} catch (IOException ignored) {}
				} else {
					result.archiveFile.delete();
				}
				if (result.preparedZipFile != null) {
					result.preparedZipFile.delete();
				}
				result.lease.close();
				ClickableToast.show(R.string.local_archive_update_failed);
				return;
			}
			ArrayList<DownloadResult> results = new ArrayList<>();
			results.add(createDownload(".nomedia", new ByteArrayInputStream(new byte[0])));
			// Save the self-contained HTML-only ZIP before the loose files and the potentially long media queue.
			// If media was requested, the coordinator replaces it with the complete ZIP after downloads finish.
			if (preparedZipInput != null) {
				results.add(createDownload(result.archiveName + ".zip", preparedZipInput));
			}
			results.add(createDownload(result.archiveName + ".json",
					new ByteArrayInputStream(result.manifest)));
			results.add(createDownload(result.archiveName + ".html", archiveInput));
			results.add(createDownload(result.resourceDirectory + "/" + DIRECTORY_THUMBNAILS,
					result.thumbnailsToDownload, false));
			results.add(createDownload(result.resourceDirectory + "/" + DIRECTORY_FILES,
					result.filesToDownload, false));
			DownloadResult enqueueResult = targetBinder -> {
				String localArchiveId = LocalArchiveManager.createDefaultId(result.archiveName);
				try (DownloadService.Accumulate ignored = targetBinder.accumulateLocalArchive(localArchiveId)) {
					for (DownloadResult innerDownloadResult : results) {
						innerDownloadResult.run(targetBinder);
					}
				}
			};
			new ArchiveCoordinator(binder, result, enqueueResult).start();
		}
	}

	private long lastNotifyIncrement = 0L;
	private int progress = 0;

	public void notifyIncrement() {
		progress++;
		long t = SystemClock.elapsedRealtime();
		if (t - lastNotifyIncrement >= 100) {
			lastNotifyIncrement = t;
			notifyProgress(progress);
		}
	}

	private static DownloadResult createDownload(String name, InputStream input) {
		return binder -> binder.downloadDirect(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE, name, input);
	}

	private static DownloadResult createDownload(String path,
			List<DownloadService.DownloadItem> downloadItems, boolean overwrite) {
		return binder -> {
			if (path != null && downloadItems.size() > 0) {
				binder.downloadDirect(DataFile.Target.DOWNLOADS,
						DIRECTORY_ARCHIVE + "/" + path, overwrite, downloadItems);
			}
		};
	}

	private static class ArchiveCoordinator implements DownloadService.Callback, PackLocalArchiveTask.Callback {
		private static final HashSet<ArchiveCoordinator> ACTIVE = new HashSet<>();

		private final DownloadService.Binder binder;
		private final Result result;
		private final DownloadResult enqueueResult;
		private int htmlRemaining = 1;
		private int manifestRemaining = 1;
		private int preparedZipRemaining;
		private final HashSet<String> filesRemaining;
		private final HashSet<String> thumbnailsRemaining;
		private boolean requiredFilesSuccess = true;
		private boolean mediaSuccess = true;
		private boolean zipFailed;
		private final HashSet<String> successfulMedia = new HashSet<>();
		private final HashMap<String, Integer> mediaIndices = new HashMap<>();
		private boolean finished;

		private ArchiveCoordinator(DownloadService.Binder binder, Result result, DownloadResult enqueueResult) {
			this.binder = binder;
			this.result = result;
			this.enqueueResult = enqueueResult;
			preparedZipRemaining = result.preparedZipFile != null ? 1 : 0;
			filesRemaining = obtainNamesSet(result.filesToDownload);
			thumbnailsRemaining = obtainNamesSet(result.thumbnailsToDownload);
			for (int i = 0; i < result.requiredMedia.size(); i++) mediaIndices.put(result.requiredMedia.get(i).path, i);
		}

		public void start() {
			trace(result.diagnosticId, "stage=queue_start originals=" + filesRemaining.size() + " thumbnails="
					+ thumbnailsRemaining.size() + " required=" + result.requiredMedia.size());
			synchronized (ACTIVE) {
				ACTIVE.add(this);
			}
			binder.register(this);
			enqueueResult.run(binder);
		}

		@Override
		public void onFinishDownloading(boolean success, DataFile.Target target, String path, String name) {
			if (finished || target != DataFile.Target.DOWNLOADS) {
				return;
			}
			String archivePath = DIRECTORY_ARCHIVE + "/" + result.resourceDirectory;
			boolean handled = false;
			if (DIRECTORY_ARCHIVE.equals(path) && (result.archiveName + ".html").equals(name)
					&& htmlRemaining > 0) {
				htmlRemaining--;
				handled = true;
			} else if (DIRECTORY_ARCHIVE.equals(path) && (result.archiveName + ".json").equals(name)
					&& manifestRemaining > 0) {
				manifestRemaining--;
				handled = true;
			} else if (DIRECTORY_ARCHIVE.equals(path) && (result.archiveName + ".zip").equals(name)
					&& preparedZipRemaining > 0) {
				preparedZipRemaining--;
				handled = true;
			} else if ((archivePath + "/" + DIRECTORY_FILES).equals(path)
					&& filesRemaining.remove(name)) {
				handled = true;
			} else if ((archivePath + "/" + DIRECTORY_THUMBNAILS).equals(path)
					&& thumbnailsRemaining.remove(name)) {
				handled = true;
			}
			if (handled) {
				boolean resource = (archivePath + "/" + DIRECTORY_FILES).equals(path)
						|| (archivePath + "/" + DIRECTORY_THUMBNAILS).equals(path);
				String kind = resource ? ((archivePath + "/" + DIRECTORY_FILES).equals(path) ? "original" : "thumbnail")
						: name.endsWith(".html") ? "html" : name.endsWith(".json") ? "manifest" : "initial_zip";
				String resourcePath = resource ? result.resourceDirectory + "/"
						+ ("original".equals(kind) ? DIRECTORY_FILES : DIRECTORY_THUMBNAILS) + "/" + name : null;
				if (resource && success) successfulMedia.add(resourcePath);
				trace(result.diagnosticId, "stage=download_result kind=" + kind + " file="
						+ (resource ? mediaIndices.get(resourcePath) : -1) + " success=" + success
						+ " pending=" + (htmlRemaining + manifestRemaining + preparedZipRemaining + filesRemaining.size() + thumbnailsRemaining.size()));
				if (!resource) requiredFilesSuccess &= success;
				else mediaSuccess &= success;
				if (htmlRemaining == 0 && manifestRemaining == 0
						&& preparedZipRemaining == 0 && filesRemaining.isEmpty() && thumbnailsRemaining.isEmpty()) {
					finished = true;
					ConcurrentUtils.HANDLER.post(this::finishDownloads);
				}
			}
		}

		private void finishDownloads() {
			binder.unregister(this);
			synchronized (ACTIVE) {
				ACTIVE.remove(this);
			}
			ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
				ArrayList<String> files = new ArrayList<>(), thumbnails = new ArrayList<>();
				validateAndSaveReceipts(files, thumbnails);
				ConcurrentUtils.HANDLER.post(() -> finishMedia(files, thumbnails));
			});
		}

		private void validateAndSaveReceipts(ArrayList<String> files, ArrayList<String> thumbnails) {
				LocalArchiveMediaStore mediaStore = new LocalArchiveMediaStore(null, result.resourceDirectory);
				JSONObject receipts = new JSONObject();
				int index = 0;
				for (LocalArchiveMediaPlan.Asset asset : result.requiredMedia) {
					long actual = mediaStore.size(asset.path);
					boolean delivered = asset.reused || successfulMedia.contains(asset.path);
					String reason = asset.missing ? "source_missing" : !delivered ? "download_failed" : actual < 0 ? "storage_unreadable"
							: actual == 0 ? "empty_file" : asset.reused && actual != asset.expectedSize ? "receipt_size_changed" : "ok";
					boolean valid = "ok".equals(reason);
					mediaSuccess &= valid;
					if (valid) try { receipts.put(asset.path, actual); } catch (JSONException e) { requiredFilesSuccess = false; }
					trace(result.diagnosticId, "stage=validate file=" + index++ + " reused=" + asset.reused
							+ " reported_bytes=" + asset.reportedSize + " verified_bytes=" + asset.expectedSize
							+ " actual_bytes=" + actual + " transfer_ok=" + delivered + " reason=" + reason);
					(LocalArchiveMediaPlan.child(result.resourceDirectory, DIRECTORY_FILES, asset.path) ? files : thumbnails).add(asset.name());
				}
				if (requiredFilesSuccess) {
					try {
						JSONObject manifest = new JSONObject(new String(result.manifest, StandardCharsets.UTF_8));
						manifest.put("mediaBytes", receipts);
						LocalArchiveManager.writeDefaultManifest(result.archiveName, manifest);
						trace(result.diagnosticId, "stage=receipts_saved count=" + receipts.length() + " media_complete=" + mediaSuccess);
					} catch (IOException | JSONException | RuntimeException e) {
						requiredFilesSuccess = false;
						trace(result.diagnosticId, "stage=receipts_failed type=" + e.getClass().getSimpleName());
					}
				}
		}

		private void finishMedia(ArrayList<String> files, ArrayList<String> thumbnails) {
			if (requiredFilesSuccess && mediaSuccess && result.createZip) {
				trace(result.diagnosticId, "stage=pack_start originals=" + files.size() + " thumbnails=" + thumbnails.size());
				PackLocalArchiveTask task = new PackLocalArchiveTask(this, result.archiveName, result.resourceDirectory, files, thumbnails);
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			} else {
				complete(requiredFilesSuccess && mediaSuccess);
			}
		}

		@Override
		public void onCleanup() {
			if (!finished) {
				trace(result.diagnosticId, "stage=download_cancelled pending="
						+ (htmlRemaining + manifestRemaining + preparedZipRemaining + filesRemaining.size() + thumbnailsRemaining.size()));
				finished = true;
				ConcurrentUtils.HANDLER.post(() -> {
					binder.unregister(this);
					synchronized (ACTIVE) {
						ACTIVE.remove(this);
					}
					ConcurrentUtils.PARALLEL_EXECUTOR.execute(
							() -> {
							try {
								if (htmlRemaining == 0 && manifestRemaining == 0 && requiredFilesSuccess) {
									validateAndSaveReceipts(new ArrayList<>(), new ArrayList<>());
								}
								if (htmlRemaining != 0 || manifestRemaining != 0 || !requiredFilesSuccess) LocalArchiveManager.deleteDefault(result.archiveName);
							} finally { result.lease.close(); }
							});
				});
			}
		}

		@Override
		public void onPackLocalArchiveComplete(boolean success) {
			zipFailed = !success;
			trace(result.diagnosticId, "stage=pack_end success=" + success);
			if (success && result.replacedArchive == null) ClickableToast.show(R.string.zip_archive_created);
			complete(success);
		}

		private void complete(boolean success) {
			trace(result.diagnosticId, "stage=complete success=" + success + " previous=" + (result.replacedArchive != null)
					+ " reason=" + (success ? "ok" : !requiredFilesSuccess ? "metadata_failed" : zipFailed ? "zip_failed" : "media_incomplete"));
			if (success && result.replacedArchive != null) {
				ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
					boolean deleted;
					try { deleted = LocalArchiveManager.deleteSuperseded(result.archiveName); }
					catch (RuntimeException e) { deleted = false; }
					finally { result.lease.close(); }
					boolean cleaned = deleted;
					trace(result.diagnosticId, "stage=old_snapshots_cleanup success=" + cleaned);
					ConcurrentUtils.HANDLER.post(() -> {
						if (cleaned) {
							ClickableToast.show(MainApplication.getInstance().getString(
									R.string.local_archive_updated__format,
									result.previousPostsCount, result.postsCount));
						} else {
							ClickableToast.show(R.string.local_archive_replace_failed);
						}
					});
				});
			} else if (!success) {
				ConcurrentUtils.PARALLEL_EXECUTOR.execute(() -> {
					try { if (!requiredFilesSuccess) LocalArchiveManager.deleteDefault(result.archiveName); }
					finally { result.lease.close(); }
				});
				boolean previous = result.replacedArchive != null;
				ClickableToast.show(!requiredFilesSuccess ? (previous ? R.string.local_archive_update_failed : R.string.local_archive_save_failed)
						: zipFailed ? (previous ? R.string.local_archive_update_zip_failed : R.string.local_archive_save_zip_failed)
						: previous ? R.string.local_archive_update_partial : R.string.local_archive_save_partial);
			} else result.lease.close();
		}
	}

	private static HashSet<String> obtainNamesSet(List<DownloadService.DownloadItem> downloadItems) {
		HashSet<String> names = new HashSet<>(downloadItems.size());
		for (DownloadService.DownloadItem downloadItem : downloadItems) {
			names.add(downloadItem.name);
		}
		return names;
	}

	private static String chooseArchiveName(String baseName, LocalArchiveUpdateLease lease) {
		HashSet<String> existingNames = new HashSet<>();
		List<DataFile> children = DataFile.obtain(DataFile.Target.DOWNLOADS, DIRECTORY_ARCHIVE).getChildren();
		if (children != null) {
			for (DataFile child : children) {
				String name = child.getName();
				if (!child.isDirectory()) {
					String extension = StringUtils.getFileExtension(name);
					if ("html".equalsIgnoreCase(extension) || "htm".equalsIgnoreCase(extension)
							|| "zip".equalsIgnoreCase(extension)
							|| "json".equalsIgnoreCase(extension)) {
						name = name.substring(0, name.length() - extension.length() - 1);
					}
				}
				existingNames.add(name.toLowerCase(Locale.ROOT));
			}
		}
		String archiveName = baseName;
		int index = 1;
		while (existingNames.contains(archiveName.toLowerCase(Locale.ROOT)) || !lease.reserveName(archiveName)) {
			archiveName = baseName + "_" + index++;
		}
		return archiveName;
	}

	private static class DeleteOnCloseFileInputStream extends FilterInputStream {
		private final File file;

		public DeleteOnCloseFileInputStream(File file) throws FileNotFoundException {
			super(new FileInputStream(file));
			this.file = file;
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				file.delete();
			}
		}
	}

	public static class Result {
		public final File archiveFile;
		public final File preparedZipFile;
		public final String archiveName;
		public final byte[] manifest;
		public final List<DownloadService.DownloadItem> filesToDownload;
		public final List<DownloadService.DownloadItem> thumbnailsToDownload;
		public final boolean createZip;
		public final LocalArchiveManager.Item replacedArchive;
		public final int previousPostsCount;
		public final int postsCount;
		public final String resourceDirectory;
		public final List<LocalArchiveMediaPlan.Asset> requiredMedia;
		public final LocalArchiveUpdateLease lease;
		public final String diagnosticId;

		private Result(File archiveFile, File preparedZipFile, String archiveName, byte[] manifest,
				List<DownloadService.DownloadItem> filesToDownload,
				List<DownloadService.DownloadItem> thumbnailsToDownload, boolean createZip,
				LocalArchiveManager.Item replacedArchive, int previousPostsCount, int postsCount, String resourceDirectory,
				List<LocalArchiveMediaPlan.Asset> requiredMedia, LocalArchiveUpdateLease lease, String diagnosticId) {
			this.archiveFile = archiveFile;
			this.preparedZipFile = preparedZipFile;
			this.archiveName = archiveName;
			this.manifest = manifest;
			this.filesToDownload = filesToDownload;
			this.thumbnailsToDownload = thumbnailsToDownload;
			this.createZip = createZip;
			this.replacedArchive = replacedArchive;
			this.previousPostsCount = previousPostsCount;
			this.postsCount = postsCount;
			this.resourceDirectory = resourceDirectory;
			this.requiredMedia = requiredMedia;
			this.lease = lease;
			this.diagnosticId = diagnosticId;
		}
	}

	private static String resourcePath(Uri uri) {
		String path = uri != null && LocalArchiveManager.RESOURCE_SCHEME.equals(uri.getScheme()) ? uri.getPath() : null;
		return path != null && path.startsWith("/") ? path.substring(1) : null;
	}
	private static String remoteSource(Uri uri) { return uri != null && resourcePath(uri) == null ? uri.toString() : null; }
	private static String sourceIdentity(Uri uri, Map<String, String> sources) {
		String path = resourcePath(uri);
		return path != null ? sources.get(path) : remoteSource(uri);
	}
	private boolean sameAttachment(Post.Attachment.File first, Post.Attachment.File second, Map<String, String> sources) {
		Uri a = first.fileUri != null ? first.fileUri : first.thumbnailUri;
		Uri b = second.fileUri != null ? second.fileUri : second.thumbnailUri;
		if (Objects.equals(a, b)) return true;
		String oldSource = sourceIdentity(a, sources), newSource = sourceIdentity(b, sources);
		if (oldSource != null && newSource != null) {
			return chan.locator.convert(Uri.parse(oldSource)).equals(chan.locator.convert(Uri.parse(newSource)));
		}
		String name = b != null ? chan.locator.createAttachmentFileName(b) : "";
		return a != null && !name.isEmpty() && name.equals(a.getLastPathSegment())
				&& first.size == second.size && first.width == second.width && first.height == second.height
				&& Objects.equals(StringUtils.getNormalizedOriginalName(first.originalName, name),
						StringUtils.getNormalizedOriginalName(second.originalName, name));
	}
	private Post.Attachment.File findPreviousFile(Post previous, Post.Attachment.File current, Map<String, String> sources) {
		if (previous != null) for (Post.Attachment attachment : previous.attachments) {
			if (attachment instanceof Post.Attachment.File && sameAttachment((Post.Attachment.File) attachment, current, sources)) {
				return (Post.Attachment.File) attachment;
			}
		}
		return null;
	}
	private List<Post.Attachment> mergeAttachments(Post current, Post previous, Map<String, String> sources) {
		ArrayList<Post.Attachment> merged = new ArrayList<>(current.attachments);
		if (previous != null) for (Post.Attachment attachment : previous.attachments) {
			if (!(attachment instanceof Post.Attachment.File)) continue;
			boolean present = false;
			for (Post.Attachment other : current.attachments) if (other instanceof Post.Attachment.File
					&& sameAttachment((Post.Attachment.File) attachment, (Post.Attachment.File) other, sources)) { present = true; break; }
			if (!present) merged.add(attachment);
		}
		return merged;
	}

	private String formatComment(String comment, Object[] decodeTo, ArrayList<SpanItem> spanItems,
			ArrayList<SpanEvent> spanEvents) {
		CharSequence charSequence = HtmlParser.spanify(comment, chan.markup.getMarkup(), null, null, this);
		SpannableStringBuilder spannable = new SpannableStringBuilder(charSequence);
		spanItems.clear();
		spanEvents.clear();
		Object[] spans = spannable.getSpans(0, spannable.length(), Object.class);
		int tagsLength = 0;
		for (Object span : spans) {
			getSpanType(span, decodeTo);
			int what = (int) decodeTo[0];
			if (what == 0) {
				continue;
			}
			int start = spannable.getSpanStart(span);
			int end = spannable.getSpanEnd(span);
			if (start < 0 || end <= start) {
				continue;
			}
			Object extra = decodeTo[1];
			if (what == ChanMarkup.TAG_SPECIAL_LINK) {
				String text = spannable.subSequence(start, end).toString();
				if (text.startsWith(">>")) {
					Uri uri = chan.locator.validateClickedUriString((String) decodeTo[1], boardName, threadNumber);
					if (threadNumber.equals(chan.locator.safe(false).getThreadNumber(uri))) {
						PostNumber postNumber = chan.locator.safe(false).getPostNumber(uri);
						String postNumberString = postNumber == null ? threadNumber : postNumber.toString();
						extra = "#" + postNumberString;
					} else {
						extra = uri.toString();
					}
				}
			}
			SpanItem spanItem = makeSpanItem(what, extra);
			if (spanItem != null) {
				spanItem.start = start;
				spanItem.end = end;
				int order = spanItems.size();
				spanItems.add(spanItem);
				spanEvents.add(new SpanEvent(start, true, spanItem, order));
				spanEvents.add(new SpanEvent(end, false, spanItem, order));
				tagsLength += spanItem.openTag.length() + spanItem.closeTag.length();
			}
		}
		spanEvents.sort((first, second) -> {
			int result = Integer.compare(first.offset, second.offset);
			if (result != 0) {
				return result;
			}
			if (first.opening != second.opening) {
				return first.opening ? 1 : -1;
			}
			if (first.opening) {
				result = Integer.compare(second.spanItem.end, first.spanItem.end);
				return result != 0 ? result : Integer.compare(first.order, second.order);
			} else {
				result = Integer.compare(second.spanItem.start, first.spanItem.start);
				return result != 0 ? result : Integer.compare(second.order, first.order);
			}
		});
		StringBuilder builder = new StringBuilder(spannable.length() + tagsLength);
		int offset = 0;
		for (SpanEvent event : spanEvents) {
			appendCommentText(builder, spannable, offset, event.offset);
			builder.append(event.opening ? event.spanItem.openTag : event.spanItem.closeTag);
			offset = event.offset;
		}
		appendCommentText(builder, spannable, offset, spannable.length());
		return builder.toString();
	}

	private static void appendCommentText(StringBuilder builder, CharSequence text, int start, int end) {
		for (int i = start; i < end; i++) {
			switch (text.charAt(i)) {
				case '<': {
					builder.append("&lt;");
					break;
				}
				case '>': {
					builder.append("&gt;");
					break;
				}
				case '\r': {
					break;
				}
				case '\n': {
					builder.append("<br />");
					break;
				}
				default: {
					builder.append(text.charAt(i));
					break;
				}
			}
		}
	}

	private SpanItem makeSpanItem(int what, Object extra) {
		String openTag;
		String closeTag;
		switch (what) {
			case ChanMarkup.TAG_BOLD: {
				openTag = "<b>";
				closeTag = "</b>";
				break;
			}
			case ChanMarkup.TAG_ITALIC: {
				openTag = "<i>";
				closeTag = "</i>";
				break;
			}
			case ChanMarkup.TAG_UNDERLINE: {
				openTag = "<span class=\"underline\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_OVERLINE: {
				openTag = "<span class=\"overline\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_STRIKE: {
				openTag = "<span class=\"strike\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_SUBSCRIPT: {
				openTag = "<sub>";
				closeTag = "</sub>";
				break;
			}
			case ChanMarkup.TAG_SUPERSCRIPT: {
				openTag = "<sup>";
				closeTag = "</sup>";
				break;
			}
			case ChanMarkup.TAG_SPOILER: {
				openTag = "<span class=\"spoiler\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_QUOTE: {
				openTag = "<span class=\"unkfunc\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_CODE: {
				openTag = "<span class=\"code\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_ASCII_ART: {
				openTag = "<span class=\"aa\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_HEADING: {
				openTag = "<span class=\"heading\">";
				closeTag = "</span>";
				break;
			}
			case ChanMarkup.TAG_SPECIAL_LINK: {
				openTag = "<a href=\"" + extra + "\">";
				closeTag = "</a>";
				break;
			}
			case ChanMarkup.TAG_SPECIAL_COLOR: {
				openTag = "<span style=\"color: #" + String.format("%06x", 0x00ffffff & (int) extra) + "\">";
				closeTag = "</span>";
				break;
			}
			default: {
				return null;
			}
		}
		return new SpanItem(openTag, closeTag);
	}

	private Object[] getSpanType(Object span, Object[] result) {
		result[0] = 0;
		result[1] = null;
		if (span instanceof LinkSpan) {
			result[0] = ChanMarkup.TAG_SPECIAL_LINK;
			result[1] = ((LinkSpan) span).uriString;
		} else if (span instanceof SpoilerSpan) {
			result[0] = ChanMarkup.TAG_SPOILER;
		} else if (span instanceof QuoteSpan) {
			result[0] = ChanMarkup.TAG_QUOTE;
		} else if (span instanceof ScriptSpan) {
			result[0] = ((ScriptSpan) span).isSuperscript() ? ChanMarkup.TAG_SUPERSCRIPT : ChanMarkup.TAG_SUBSCRIPT;
		} else if (span instanceof MediumSpan) {
			result[0] = ChanMarkup.TAG_BOLD;
		} else if (span instanceof ItalicSpan) {
			result[0] = ChanMarkup.TAG_ITALIC;
		} else if (span instanceof UnderlineSpan) {
			result[0] = ChanMarkup.TAG_UNDERLINE;
		} else if (span instanceof OverlineSpan) {
			result[0] = ChanMarkup.TAG_OVERLINE;
		} else if (span instanceof StrikethroughSpan) {
			result[0] = ChanMarkup.TAG_STRIKE;
		} else if (span instanceof GainedColorSpan) {
			result[0] = ChanMarkup.TAG_SPECIAL_COLOR;
			result[1] = ((GainedColorSpan) span).getForegroundColor();
		} else if (span instanceof MonospaceSpan) {
			result[0] = ((MonospaceSpan) span).isAsciiArt() ? ChanMarkup.TAG_ASCII_ART : ChanMarkup.TAG_CODE;
		} else if (span instanceof HeadingSpan) {
			result[0] = ChanMarkup.TAG_HEADING;
		}
		return result;
	}

}
