package com.mishiranu.dashchan.content.async;

import android.net.Uri;
import chan.content.Chan;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.http.HttpResponse;
import com.mishiranu.dashchan.content.model.ErrorItem;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RetryableMediaDownload {
	private static final int MAX_RECONNECTS = 10;
	private static final int MAX_STALLED_FAILURES = 3;
	private static final Pattern PATTERN_BYTES = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)",
			Pattern.CASE_INSENSITIVE);

	interface Callback {
		boolean isCancelled();
		default void onInit(long total) {}
		default void onBytes(byte[] buffer, int count) {}
		default void onProgress(long position, long total) {}
		default void onRestart() {}
	}

	static class RangeNotSupportedException extends IOException {}

	private static class ContentRange {
		public final long start;
		public final long end;
		public final long total;

		private ContentRange(long start, long end, long total) {
			this.start = start;
			this.end = end;
			this.total = total;
		}
	}

	private static class RetryState {
		private int reconnects;
		private int stalledFailures;
		private long lastProgress;

		public RetryState(long initialPosition) {
			lastProgress = initialPosition;
		}

		public boolean prepareRetry(long position) {
			if (position > lastProgress) {
				lastProgress = position;
				stalledFailures = 0;
			} else {
				stalledFailures++;
			}
			if (reconnects >= MAX_RECONNECTS || stalledFailures >= MAX_STALLED_FAILURES) {
				return false;
			}
			reconnects++;
			return true;
		}

		public long getDelay() {
			return Math.min(250L << Math.min(stalledFailures, 3), 2000L);
		}
	}

	private RetryableMediaDownload() {}

	public static long download(Chan chan, Uri uri, HttpHolder holder, File destination,
			long initialStart, int connectTimeout, int readTimeout, Callback callback)
			throws ExtensionException, HttpException, InvalidResponseException, IOException {
		File parent = destination.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		long position = Math.max(initialStart, 0L);
		if (initialStart <= 0L) {
			try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
				output.setLength(0L);
			}
		}
		long total = -1L;
		long knownTotal = -1L;
		boolean initialized = false;
		boolean restartedWithoutRange = false;
		RetryState retryState = new RetryState(position);
		while (true) {
			if (callback.isCancelled()) {
				throw new InterruptedIOException("Cancelled");
			}
			boolean rangeRequest = position > 0L;
			HttpResponse response = null;
			try {
				ChanPerformer.ReadContentResult result = chan.performer.safe().onReadContent(
						new ChanPerformer.ReadContentData(uri, connectTimeout, readTimeout, holder,
								rangeRequest ? position : -1L, -1L));
				response = result != null ? result.response : null;
				if (response == null) {
					throw new HttpException(ErrorItem.Type.DOWNLOAD, false, false);
				}
			} catch (HttpException e) {
				if (response != null) {
					response.cleanupAndDisconnect();
				}
				if (e.isSocketException() && position > initialStart && retryState.prepareRetry(position)
						&& waitBeforeRetry(retryState, callback)) {
					continue;
				}
				throw e;
			}

			long expectedEnd;
			if (rangeRequest) {
				ContentRange contentRange = response.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
						? parseContentRange(response) : null;
				if (contentRange == null || contentRange.start != position
						|| contentRange.end != contentRange.total) {
					if (initialStart <= 0L && !restartedWithoutRange
							&& response.getResponseCode() == HttpURLConnection.HTTP_OK) {
						position = 0L;
						total = response.getLength();
						expectedEnd = total;
						restartedWithoutRange = true;
						try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
							output.setLength(0L);
						}
						callback.onRestart();
					} else {
						response.cleanupAndDisconnect();
						throw new RangeNotSupportedException();
					}
				} else {
					total = contentRange.total;
					expectedEnd = contentRange.end;
				}
			} else {
				total = response.getLength();
				expectedEnd = total;
			}
			if (total >= 0L) {
				if (knownTotal >= 0L && knownTotal != total) {
					response.cleanupAndDisconnect();
					throw new InvalidResponseException();
				}
				knownTotal = total;
			}

			if (!initialized) {
				initialized = true;
				callback.onInit(total);
			}
			IOException readException = null;
			HttpException openException = null;
			try {
				InputStream input = null;
				try {
					input = response.open();
				} catch (HttpException e) {
					openException = e;
				}
				if (input != null) {
					try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
						output.seek(position);
						byte[] buffer = new byte[8192];
						while (true) {
							if (callback.isCancelled()) {
								throw new InterruptedIOException("Cancelled");
							}
							int count;
							try {
								count = input.read(buffer);
							} catch (IOException e) {
								readException = e;
								break;
							}
							if (count < 0) {
								if (expectedEnd >= 0L && position < expectedEnd) {
									readException = new EOFException("Unexpected end of media stream");
								}
								break;
							}
							if (count == 0) {
								continue;
							}
							if (expectedEnd >= 0L && position + count > expectedEnd) {
								throw new InvalidResponseException();
							}
							output.write(buffer, 0, count);
							position += count;
							callback.onBytes(buffer, count);
							callback.onProgress(position, total);
						}
					}
				}
			} finally {
				response.cleanupAndDisconnect();
			}
			if (openException != null) {
				if (openException.isSocketException() && retryState.prepareRetry(position)
						&& waitBeforeRetry(retryState, callback)) {
					continue;
				}
				throw openException;
			}
			if (readException != null && expectedEnd >= 0L && position >= expectedEnd) {
				readException = null;
			}
			if (readException == null) {
				return position;
			}
			if (callback.isCancelled()) {
				throw readException;
			}
			if (!retryState.prepareRetry(position)) {
				throw response.fail(readException);
			}
			if (!waitBeforeRetry(retryState, callback)) {
				throw new InterruptedIOException("Cancelled");
			}
		}
	}

	private static ContentRange parseContentRange(HttpResponse response) {
		List<String> values = null;
		for (Map.Entry<String, List<String>> entry : response.getHeaderFields().entrySet()) {
			if (entry.getKey() != null && "Content-Range".equalsIgnoreCase(entry.getKey())) {
				values = entry.getValue();
				break;
			}
		}
		if (values == null || values.size() != 1) {
			return null;
		}
		Matcher matcher = PATTERN_BYTES.matcher(values.get(0));
		if (!matcher.matches()) {
			return null;
		}
		try {
			long start = Long.parseLong(matcher.group(1));
			long end = Long.parseLong(matcher.group(2)) + 1L;
			long total = Long.parseLong(matcher.group(3));
			return start >= 0L && end > start && total >= end ? new ContentRange(start, end, total) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean waitBeforeRetry(RetryState retryState, Callback callback) {
		try {
			Thread.sleep(retryState.getDelay());
			return !callback.isCancelled();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
