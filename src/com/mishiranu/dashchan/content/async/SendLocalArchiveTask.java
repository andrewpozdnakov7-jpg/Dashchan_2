package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanMarkup;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.LocalArchiveManager;
import com.mishiranu.dashchan.content.model.Post;
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
import org.json.JSONException;

public class SendLocalArchiveTask extends ExecutorTask<Integer, SendLocalArchiveTask.Result>
		implements ChanMarkup.MarkupExtra {
	private static final String DIRECTORY_ARCHIVE = LocalArchiveManager.DIRECTORY_ARCHIVE;
	private static final String DIRECTORY_FILES = LocalArchiveManager.DIRECTORY_FILES;
	private static final String DIRECTORY_THUMBNAILS = LocalArchiveManager.DIRECTORY_THUMBNAILS;

	private final Callback callback;
	private final Chan chan;
	private final String boardName;
	private final String threadNumber;
	private final Collection<Post> posts;
	private final boolean saveThumbnails;
	private final boolean saveFiles;
	private final boolean createZip;

	public interface DownloadResult {
		void run(DownloadService.Binder binder);
	}

	public interface Callback {
		void onLocalArchivationProgressUpdate(int handledPostsCount);
		void onLocalArchivationComplete(DownloadResult result);
	}

	public SendLocalArchiveTask(Callback callback, Chan chan, String boardName, String threadNumber,
			Collection<Post> posts, boolean saveThumbnails, boolean saveFiles, boolean createZip) {
		this.callback = callback;
		this.chan = chan;
		this.boardName = boardName;
		this.threadNumber = threadNumber;
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
		Chan chan = this.chan;
		String boardName = this.boardName;
		String threadNumber = this.threadNumber;
		Collection<Post> posts = this.posts;
		if (posts.isEmpty()) {
			return null;
		}
		Object[] decodeTo = new Object[2];
		ArrayList<SpanItem> spanItems = new ArrayList<>();
		ArrayList<SpanEvent> spanEvents = new ArrayList<>();
		String archiveName = chooseArchiveName(chan.name + '-' + boardName + '-' + threadNumber);
		HashSet<String> existFilesLc = new HashSet<>();
		HashSet<String> existThumbnailsLc = new HashSet<>();
		HashMap<String, String> iconNames = new HashMap<>();
		Hasher hasher = Hasher.getInstanceSha256();
		int totalFilesCount = 0;
		for (Post post : posts) {
			totalFilesCount += post.attachments.size();
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
		ArrayList<DownloadService.DownloadItem> filesToDownload = new ArrayList<>(
				saveFiles ? totalFilesCount : 0);
		ArrayList<DownloadService.DownloadItem> thumbnailsToDownload = new ArrayList<>(
				saveThumbnails ? totalFilesCount : 0);
		try {
			String threadTitle = posts.iterator().next().subject;
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(archiveFile), StandardCharsets.UTF_8)) {
			String defaultName = chan.configuration.getDefaultName(boardName);
			WakabaLikeHtmlBuilder htmlBuilder = new WakabaLikeHtmlBuilder(threadTitle,
					boardName, chan.configuration.getBoardTitle(boardName), chan.configuration.getTitle(),
					chan.locator.safe(false).createThreadUri(boardName, threadNumber), posts.size(), totalFilesCount);
			htmlBuilder.writeHeader(writer);
			for (Post post : posts) {
			PostNumber number = post.number;
			String name = StringUtils.emptyIfNull(post.name).trim();
			String identifier = post.identifier;
			String tripcode = post.tripcode;
			String capcode = post.capcode;
			String email = post.email;
			String subject = post.subject;
			String comment = post.comment;
			long timestamp = post.timestamp;
			boolean sage = post.isSage();
			boolean originalPoster = post.isOriginalPoster();
			boolean deleted = post.deleted;
			boolean useDefaultName = name.equals(defaultName) || name.isEmpty();
			if (name.isEmpty()) {
				name = defaultName;
			}
			comment = formatComment(comment, decodeTo, spanItems, spanEvents);
			htmlBuilder.addPost(number.toString(), subject, name, identifier, tripcode, capcode, email,
					sage, originalPoster, timestamp, deleted, useDefaultName, comment);
			for (Post.Icon icon : post.icons) {
				if (icon.uri != null && !StringUtils.isEmpty(icon.title)) {
					Uri iconUri = chan.locator.convert(icon.uri);
					String simpleUri = iconUri.buildUpon().scheme(null).authority(null).build().toString();
					String pathHash = Base64.encodeToString(hasher.calculate(simpleUri), 0, 12,
							Base64.NO_WRAP | Base64.URL_SAFE);
					String iconNameWithoutExtension = "icon-" + pathHash;
					String iconName = iconNames.get(iconNameWithoutExtension);
					boolean downloadIcon = false;
					if (iconName == null) {
						String extension = null;
						if (ChanConfiguration.SCHEME_CHAN.equals(iconUri.getScheme())) {
							ByteArrayOutputStream output = new ByteArrayOutputStream();
							try {
								if (chan.configuration.readResourceUri(iconUri, output)) {
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
						downloadIcon = true;
					}
					String iconPath = archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + iconName;
					htmlBuilder.addIcon(iconPath, icon.title);
					if (downloadIcon && saveThumbnails) {
						thumbnailsToDownload.add(new DownloadService.DownloadItem(chan.name,
								iconUri, iconName, null, null));
					}
				}
			}
			for (Post.Attachment attachment : post.attachments) {
				if (attachment instanceof Post.Attachment.File) {
					Post.Attachment.File file = (Post.Attachment.File) attachment;
					Uri fileUri = chan.locator.convert(chan.locator.fixRelativeFileUri(file.fileUri));
					Uri thumbnailUri = chan.locator.convert(chan.locator.fixRelativeFileUri(file.thumbnailUri));
					if (fileUri == null) {
						fileUri = thumbnailUri;
					}
					if (fileUri != null) {
						String fileName = chan.locator.createAttachmentFileName(fileUri);
						fileName = chooseFileName(existFilesLc, fileName);
						String filePath = archiveName + "/" + DIRECTORY_FILES + "/" + fileName;
						String thumbnailName = null;
						String thumbnailPath = null;
						if (thumbnailUri != null) {
							thumbnailName = chan.locator.createAttachmentFileName(thumbnailUri);
							thumbnailName = chooseFileName(existThumbnailsLc, thumbnailName);
							thumbnailPath = archiveName + "/" + DIRECTORY_THUMBNAILS + "/" + thumbnailName;
						}
						String originalName = StringUtils.getNormalizedOriginalName(file.originalName, fileName);
						htmlBuilder.addFile(filePath, thumbnailPath, originalName, file.size,
								file.width, file.height);
						if (saveFiles) {
							filesToDownload.add(new DownloadService.DownloadItem(chan.name,
									fileUri, fileName, null, null));
						}
						if (saveThumbnails && thumbnailUri != null) {
							thumbnailsToDownload.add(new DownloadService.DownloadItem(chan.name,
									thumbnailUri, thumbnailName, null, null));
						}
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
			byte[] manifest = LocalArchiveManager.createManifest(archiveName, chan.name, boardName,
					threadNumber, threadTitle, posts.size(), totalFilesCount, saveFiles, saveThumbnails)
					.toString(2).getBytes(StandardCharsets.UTF_8);
			success = true;
			return new Result(archiveFile, archiveName, manifest, filesToDownload, thumbnailsToDownload);
		} catch (IOException | JSONException e) {
			return null;
		} finally {
			if (!success) {
				archiveFile.delete();
			}
		}
	}

	@Override
	protected void onProgress(Integer value) {
		callback.onLocalArchivationProgressUpdate(value);
	}

	@Override
	protected void onComplete(Result result) {
		DownloadResult downloadResult = null;
		if (result != null) {
			InputStream archiveInput;
			try {
				archiveInput = new DeleteOnCloseFileInputStream(result.archiveFile);
			} catch (FileNotFoundException e) {
				result.archiveFile.delete();
				callback.onLocalArchivationComplete(null);
				return;
			}
			ArrayList<DownloadResult> results = new ArrayList<>();
			results.add(createDownload(".nomedia", new ByteArrayInputStream(new byte[0])));
			results.add(createDownload(result.archiveName + ".json",
					new ByteArrayInputStream(result.manifest)));
			results.add(createDownload(result.archiveName + ".html", archiveInput));
			results.add(createDownload(result.archiveName + "/" + DIRECTORY_THUMBNAILS,
					result.thumbnailsToDownload, createZip));
			results.add(createDownload(result.archiveName + "/" + DIRECTORY_FILES,
					result.filesToDownload, createZip));
			DownloadResult enqueueResult = binder -> {
				try (DownloadService.Accumulate ignored = binder.accumulate()) {
					for (DownloadResult innerDownloadResult : results) {
						innerDownloadResult.run(binder);
					}
				}
			};
			if (createZip) {
				downloadResult = binder -> new ZipCoordinator(binder, result, enqueueResult).start();
			} else {
				downloadResult = enqueueResult;
			}
		}
		callback.onLocalArchivationComplete(downloadResult);
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

	private static class ZipCoordinator implements DownloadService.Callback, PackLocalArchiveTask.Callback {
		private static final HashSet<ZipCoordinator> ACTIVE = new HashSet<>();

		private final DownloadService.Binder binder;
		private final Result result;
		private final DownloadResult enqueueResult;
		private int htmlRemaining = 1;
		private int manifestRemaining = 1;
		private final HashSet<String> filesRemaining;
		private final HashSet<String> thumbnailsRemaining;
		private boolean success = true;
		private boolean finished;

		private ZipCoordinator(DownloadService.Binder binder, Result result, DownloadResult enqueueResult) {
			this.binder = binder;
			this.result = result;
			this.enqueueResult = enqueueResult;
			filesRemaining = obtainNamesSet(result.filesToDownload);
			thumbnailsRemaining = obtainNamesSet(result.thumbnailsToDownload);
		}

		public void start() {
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
			String archivePath = DIRECTORY_ARCHIVE + "/" + result.archiveName;
			boolean handled = false;
			if (DIRECTORY_ARCHIVE.equals(path) && (result.archiveName + ".html").equals(name)
					&& htmlRemaining > 0) {
				htmlRemaining--;
				handled = true;
			} else if (DIRECTORY_ARCHIVE.equals(path) && (result.archiveName + ".json").equals(name)
					&& manifestRemaining > 0) {
				manifestRemaining--;
				handled = true;
			} else if ((archivePath + "/" + DIRECTORY_FILES).equals(path)) {
				handled = filesRemaining.remove(name);
			} else if ((archivePath + "/" + DIRECTORY_THUMBNAILS).equals(path)
					&& thumbnailsRemaining.remove(name)) {
				handled = true;
			}
			if (handled) {
				this.success &= success;
				if (htmlRemaining == 0 && manifestRemaining == 0
						&& filesRemaining.isEmpty() && thumbnailsRemaining.isEmpty()) {
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
			if (success) {
				PackLocalArchiveTask task = new PackLocalArchiveTask(this, result.archiveName,
						obtainNames(result.filesToDownload), obtainNames(result.thumbnailsToDownload));
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			} else {
				onPackLocalArchiveComplete(false);
			}
		}

		@Override
		public void onCleanup() {
			if (!finished) {
				finished = true;
				ConcurrentUtils.HANDLER.post(() -> {
					binder.unregister(this);
					synchronized (ACTIVE) {
						ACTIVE.remove(this);
					}
				});
			}
		}

		@Override
		public void onPackLocalArchiveComplete(boolean success) {
			ClickableToast.show(success ? R.string.zip_archive_created : R.string.zip_archive_failed);
		}
	}

	private static List<String> obtainNames(List<DownloadService.DownloadItem> downloadItems) {
		ArrayList<String> names = new ArrayList<>(downloadItems.size());
		for (DownloadService.DownloadItem downloadItem : downloadItems) {
			names.add(downloadItem.name);
		}
		return names;
	}

	private static HashSet<String> obtainNamesSet(List<DownloadService.DownloadItem> downloadItems) {
		HashSet<String> names = new HashSet<>(downloadItems.size());
		for (DownloadService.DownloadItem downloadItem : downloadItems) {
			names.add(downloadItem.name);
		}
		return names;
	}

	private static String chooseArchiveName(String baseName) {
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
		while (existingNames.contains(archiveName.toLowerCase(Locale.ROOT))) {
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
		public final String archiveName;
		public final byte[] manifest;
		public final List<DownloadService.DownloadItem> filesToDownload;
		public final List<DownloadService.DownloadItem> thumbnailsToDownload;

		private Result(File archiveFile, String archiveName, byte[] manifest,
				List<DownloadService.DownloadItem> filesToDownload,
				List<DownloadService.DownloadItem> thumbnailsToDownload) {
			this.archiveFile = archiveFile;
			this.archiveName = archiveName;
			this.manifest = manifest;
			this.filesToDownload = filesToDownload;
			this.thumbnailsToDownload = thumbnailsToDownload;
		}
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

	private String chooseFileName(HashSet<String> fileNamesLc, String fileName) {
		if (fileName != null) {
			Locale locale = Locale.ROOT;
			String fileNameLc = fileName.toLowerCase(locale);
			if (fileNamesLc.contains(fileNameLc)) {
				String extension = StringUtils.getFileExtension(fileName);
				if (extension != null) {
					fileName = fileName.substring(0, fileName.length() - extension.length() - 1);
				}
				String newFileName;
				String newFileNameLc;
				int i = 0;
				do {
					newFileName = fileName + "-" + ++i + (extension != null ? "." + extension : "");
					newFileNameLc = newFileName.toLowerCase(locale);
				} while (fileNamesLc.contains(newFileNameLc));
				fileNamesLc.add(newFileNameLc);
				fileName = newFileName;
			} else {
				fileNamesLc.add(fileNameLc);
			}
		}
		return fileName;
	}
}
