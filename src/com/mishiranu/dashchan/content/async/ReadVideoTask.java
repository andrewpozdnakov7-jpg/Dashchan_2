package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import chan.content.Chan;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.io.File;
import java.io.IOException;

public class ReadVideoTask extends HttpHolderTask<long[], Boolean> {
	private static final int CONNECT_TIMEOUT = 15000;
	private static final int READ_TIMEOUT = 15000;

	private final Callback callback;
	private final Chan chan;
	private final Uri uri;
	private final long start;
	private final File file;
	private final File partialFile;

	private ErrorItem errorItem;
	private boolean disallowRangeRequests;

	public interface Callback {
		void onReadVideoInit(File partialFile);
		void onReadVideoProgressUpdate(long progress, long progressMax);
		void onReadVideoRangeUpdate(long start, long end);
		void onReadVideoSuccess(boolean partial, File file);
		void onReadVideoFail(boolean partial, ErrorItem errorItem, boolean disallowRangeRequests);
	}

	private final TimedProgressHandler progressHandler = new TimedProgressHandler() {
		@Override
		public void onProgressChange(long progress, long progressMax) {
			notifyProgress(new long[] {progress, progressMax});
		}
	};

	public ReadVideoTask(Callback callback, Chan chan, Uri uri, long start) {
		super(chan);
		this.callback = callback;
		this.chan = chan;
		this.uri = uri;
		this.start = start;
		file = CacheManager.getInstance().getMediaFile(uri, false);
		partialFile = CacheManager.getInstance().getPartialMediaFile(uri);
	}

	@Override
	protected Boolean run(HttpHolder holder) {
		if (file == null || partialFile == null) {
			errorItem = new ErrorItem(ErrorItem.Type.NO_ACCESS_TO_MEMORY);
			return false;
		}
		boolean success = false;
		try {
			RetryableMediaDownload.download(chan, uri, holder, partialFile, start,
					CONNECT_TIMEOUT, READ_TIMEOUT, new RetryableMediaDownload.Callback() {
						@Override
						public boolean isCancelled() {
							return ReadVideoTask.this.isCancelled();
						}

						@Override
						public void onInit(long total) {
							if (start <= 0) {
								progressHandler.setInputProgressMax(total);
								ConcurrentUtils.mainGet(() -> {
									callback.onReadVideoInit(partialFile);
									return null;
								});
							}
						}

						@Override
						public void onProgress(long position, long total) {
							if (start <= 0) {
								if (total >= 0L) {
									progressHandler.setInputProgressMax(total);
								}
								progressHandler.updateProgress(position);
							} else {
								notifyProgress(new long[] {position - start});
							}
						}

						@Override
						public void onRestart() {
							if (start <= 0) {
								progressHandler.updateProgress(0L);
							}
						}
					});
			success = true;
			return true;
		} catch (RetryableMediaDownload.RangeNotSupportedException e) {
			errorItem = new ErrorItem(ErrorItem.Type.INVALID_RESPONSE);
			disallowRangeRequests = start > 0;
			return false;
		} catch (IOException e) {
			ErrorItem.Type errorType = ReadFileTask.getErrorTypeFromExceptionAndHandle(e);
			errorItem = new ErrorItem(errorType != null ? errorType : ErrorItem.Type.DOWNLOAD);
			return false;
		} catch (ExtensionException | HttpException | InvalidResponseException e) {
			errorItem = e.getErrorItemAndHandle();
			return false;
		} finally {
			if (start <= 0) {
				file.delete();
				if (success) {
					partialFile.renameTo(file);
				} else {
					partialFile.delete();
				}
				CacheManager.getInstance().handleDownloadedFile(partialFile, false);
				CacheManager.getInstance().handleDownloadedFile(file, success);
				if (chan.name != null) {
					chan.configuration.commit();
				}
			}
		}
	}

	@Override
	protected void onProgress(long[] values) {
		if (start > 0) {
			callback.onReadVideoRangeUpdate(start, start + values[0]);
		} else {
			callback.onReadVideoProgressUpdate(values[0], values[1]);
		}
	}

	@Override
	protected void onComplete(Boolean success) {
		if (success) {
			callback.onReadVideoSuccess(start > 0, file);
		} else {
			callback.onReadVideoFail(start > 0, errorItem, disallowRangeRequests);
		}
	}

	public boolean isError() {
		return errorItem != null;
	}
}
